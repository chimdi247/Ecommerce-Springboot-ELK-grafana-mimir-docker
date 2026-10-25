package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.dto.*;
import com.phegondev.Phegon.Eccormerce.entity.Order;
import com.phegondev.Phegon.Eccormerce.entity.OrderItem;
import com.phegondev.Phegon.Eccormerce.entity.Payment;
import com.phegondev.Phegon.Eccormerce.entity.ProcessedStripeEvent;
import com.phegondev.Phegon.Eccormerce.entity.User;
import com.phegondev.Phegon.Eccormerce.enums.OrderStatus;
import com.phegondev.Phegon.Eccormerce.enums.PaymentStatus;
import com.phegondev.Phegon.Eccormerce.event.PaymentSucceededEvent;
import com.phegondev.Phegon.Eccormerce.exception.InvalidCredentialsException;
import com.phegondev.Phegon.Eccormerce.exception.NotFoundException;
import com.phegondev.Phegon.Eccormerce.exception.PaymentException;
import com.phegondev.Phegon.Eccormerce.mapper.EntityDtoMapper;
import com.phegondev.Phegon.Eccormerce.payment.StripeGateway;
import com.phegondev.Phegon.Eccormerce.repository.CartRepo;
import com.phegondev.Phegon.Eccormerce.repository.OrderRepo;
import com.phegondev.Phegon.Eccormerce.repository.PaymentRepo;
import com.phegondev.Phegon.Eccormerce.repository.ProcessedStripeEventRepo;
import com.phegondev.Phegon.Eccormerce.service.interf.PaymentService;
import com.phegondev.Phegon.Eccormerce.service.interf.UserService;
import com.phegondev.Phegon.Eccormerce.telemetry.BusinessMetrics;
import com.phegondev.Phegon.Eccormerce.telemetry.Tracing;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Stripe payments with layered protection against paying an order twice:
 *
 * <ol>
 *   <li><b>Row lock</b>: create/confirm run under {@code SELECT ... FOR UPDATE} on the order, so
 *       concurrent requests for one order are serialized.</li>
 *   <li><b>State guard</b>: an order in {@code PAID} can never create another PaymentIntent (409).</li>
 *   <li><b>One payment per order</b>: unique {@code payments.order_id}; an open PaymentIntent is
 *       reused instead of creating a second one, even if the client sends a different key.</li>
 *   <li><b>Idempotency-Key</b>: the client key (scoped to the order) is stored and also sent to
 *       Stripe, so a retried request replays the same PaymentIntent.</li>
 *   <li><b>Idempotent finalization</b>: confirm endpoint and webhook converge on {@code markPaid},
 *       which is a no-op for an already paid order (no duplicate e-mail), and webhook event ids
 *       are de-duplicated.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{8,128}$");
    private static final Set<String> HANDLED_EVENTS = Set.of(
            "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.canceled");

    private final OrderRepo orderRepo;
    private final PaymentRepo paymentRepo;
    private final ProcessedStripeEventRepo processedEventRepo;
    private final CartRepo cartRepo;
    private final UserService userService;
    private final StripeGateway stripe;
    private final EntityDtoMapper entityDtoMapper;
    private final ApplicationEventPublisher events;
    private final AppProperties properties;

    // ------------------------------------------------------------------------------ config
    @Override
    public Response getConfig() {
        AppProperties.Payment config = properties.getPayment();
        if (config.getPublishableKey() == null || config.getPublishableKey().isBlank()) {
            throw new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not configured (STRIPE_PUBLISHABLE_KEY is empty)");
        }
        return Response.builder()
                .status(200)
                .paymentConfig(new PaymentConfigDto(config.getPublishableKey(), config.getCurrency().toLowerCase()))
                .build();
    }

    // ------------------------------------------------------------------------------ create intent
    @Override
    @Transactional(noRollbackFor = PaymentException.class)
    public Response createPaymentIntent(Long orderId, String idempotencyKey) {
        return Tracing.inSpan("payment.create_intent", Map.of("order.id", String.valueOf(orderId)),
                () -> doCreatePaymentIntent(orderId, idempotencyKey));
    }

    private Response doCreatePaymentIntent(Long orderId, String clientKey) {
        if (clientKey == null || !IDEMPOTENCY_KEY.matcher(clientKey).matches()) {
            throw new InvalidCredentialsException("Idempotency-Key header is required (8-128 chars: letters, digits, _ . : -)");
        }

        User user = userService.getLoginUser();
        Order order = lockOwnedOrder(orderId, user);

        // (2) state guard
        if (isPaid(order)) {
            BusinessMetrics.payment("already_paid_blocked");
            log.warn("Blocked second payment attempt", kv("event", "payment_blocked_already_paid"), kv("orderId", orderId));
            throw new PaymentException(HttpStatus.CONFLICT, "Order " + orderId + " has already been paid");
        }

        // key is scoped to the order so one client key can never collide across orders
        String scopedKey = "order-" + orderId + "-" + clientKey;
        Payment payment = paymentRepo.findByOrderId(orderId).orElse(null);

        // (3)+(4) reuse the open PaymentIntent / replay the same key
        if (payment != null && payment.getStripePaymentIntentId() != null) {
            PaymentIntent existing = stripe.retrievePaymentIntent(payment.getStripePaymentIntentId());
            String state = String.valueOf(existing.getStatus());

            if ("succeeded".equals(state)) {
                // We missed the webhook / confirm call: heal our state, then refuse.
                markPaid(order, payment, existing);
                BusinessMetrics.payment("already_paid_blocked");
                throw new PaymentException(HttpStatus.CONFLICT, "Order " + orderId + " has already been paid");
            }

            if (!"canceled".equals(state)) {
                boolean replay = scopedKey.equals(payment.getIdempotencyKey());
                BusinessMetrics.payment(replay ? "replayed" : "reused");
                log.info("Returning existing PaymentIntent", kv("event", replay ? "payment_replayed" : "payment_reused"),
                        kv("orderId", orderId), kv("paymentIntentId", existing.getId()), kv("stripeStatus", state));
                reopen(order, payment);
                return intentResponse(orderId, existing, true);
            }
            // canceled intent: fall through and create a fresh one
        }

        BigDecimal total = payableTotal(order);
        String currency = properties.getPayment().getCurrency().toLowerCase();
        long minorUnits = toMinorUnits(total, currency);

        PaymentIntent created = stripe.createPaymentIntent(minorUnits, currency,
                Map.of("orderId", String.valueOf(orderId), "userId", String.valueOf(user.getId())), scopedKey);

        if (payment == null) {
            payment = new Payment();
            payment.setOrder(order);
        }
        payment.setAmount(total);
        payment.setCurrency(currency);
        payment.setMethod("card");
        payment.setStatus(PaymentStatus.PENDING.name());
        payment.setStripePaymentIntentId(created.getId());
        payment.setIdempotencyKey(scopedKey);
        payment.setFailureMessage(null);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepo.save(payment);

        order.setPaymentStatus(PaymentStatus.PENDING);
        orderRepo.save(order);

        BusinessMetrics.payment("initiated");
        log.info("PaymentIntent created", kv("event", "payment_initiated"), kv("orderId", orderId),
                kv("paymentIntentId", created.getId()), kv("amountMinorUnits", minorUnits), kv("currency", currency));
        return intentResponse(orderId, created, false);
    }

    // ------------------------------------------------------------------------------ confirm
    @Override
    @Transactional(noRollbackFor = PaymentException.class)
    public Response confirmPayment(Long orderId) {
        return Tracing.inSpan("payment.confirm", Map.of("order.id", String.valueOf(orderId)), () -> {
            User user = userService.getLoginUser();
            Order order = lockOwnedOrder(orderId, user);
            Payment payment = paymentRepo.findByOrderId(orderId)
                    .orElseThrow(() -> new NotFoundException("No payment has been started for this order"));

            if (isPaid(order)) {
                return statusResponse(order, payment);   // idempotent: nothing to do, no second e-mail
            }

            PaymentIntent intent = stripe.retrievePaymentIntent(payment.getStripePaymentIntentId());
            applyIntentState(order, payment, intent);
            return statusResponse(order, payment);
        });
    }

    // ------------------------------------------------------------------------------ status
    @Override
    @Transactional(readOnly = true)
    public Response getPaymentStatus(Long orderId) {
        User user = userService.getLoginUser();
        Order order = orderRepo.findById(orderId).orElseThrow(() -> new NotFoundException("Order Not Found"));
        assertOwner(order, user);
        return statusResponse(order, paymentRepo.findByOrderId(orderId).orElse(null));
    }

    // ------------------------------------------------------------------------------ webhook
    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        Event event = stripe.verifyWebhook(payload, signatureHeader);

        if (processedEventRepo.existsById(event.getId())) {
            log.info("Duplicate Stripe event ignored", kv("event", "webhook_duplicate"), kv("stripeEventId", event.getId()));
            return;
        }

        if (HANDLED_EVENTS.contains(event.getType())) {
            PaymentIntent intent = stripe.extractPaymentIntent(event);
            if (intent != null) {
                paymentRepo.findByStripePaymentIntentId(intent.getId()).ifPresentOrElse(payment -> {
                    Order order = orderRepo.findByIdForUpdate(payment.getOrder().getId())
                            .orElseThrow(() -> new NotFoundException("Order Not Found"));
                    applyIntentState(order, payment, intent);
                }, () -> log.warn("Webhook for unknown PaymentIntent", kv("paymentIntentId", intent.getId())));
            }
        }
        processedEventRepo.save(new ProcessedStripeEvent(event.getId(), event.getType()));
        log.info("Stripe event processed", kv("event", "webhook_processed"), kv("stripeEventId", event.getId()), kv("stripeType", event.getType()));
    }

    // ------------------------------------------------------------------------------ state machine
    private void applyIntentState(Order order, Payment payment, PaymentIntent intent) {
        switch (String.valueOf(intent.getStatus())) {
            case "succeeded" -> markPaid(order, payment, intent);
            case "canceled" -> {
                if (!isPaid(order)) {
                    payment.setStatus(PaymentStatus.CANCELED.name());
                    order.setPaymentStatus(PaymentStatus.CANCELED);
                    touch(order, payment);
                }
            }
            case "requires_payment_method" -> {
                if (intent.getLastPaymentError() != null) {
                    markFailed(order, payment, intent.getLastPaymentError().getMessage());
                }
            }
            default -> { /* processing / requires_action / requires_confirmation: still pending */ }
        }
    }

    /** Single place where an order becomes PAID. Safe to call any number of times. */
    private void markPaid(Order order, Payment payment, PaymentIntent intent) {
        if (isPaid(order)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(now);
        if (order.getOrderItemList() != null) {
            order.getOrderItemList().stream()
                    .filter(item -> item.getStatus() == null || item.getStatus() == OrderStatus.PENDING)
                    .forEach(item -> item.setStatus(OrderStatus.CONFIRMED));
        }
        payment.setStatus(PaymentStatus.PAID.name());
        payment.setFailureMessage(null);
        payment.setUpdatedAt(now);
        orderRepo.save(order);
        paymentRepo.save(payment);

        Long ownerId = ownerId(order);
        if (ownerId != null) {
            cartRepo.findByUserId(ownerId).ifPresent(cart -> {
                cart.getItems().clear();
                cart.touch();
                cartRepo.save(cart);
            });
        }

        BusinessMetrics.payment("succeeded");
        BusinessMetrics.revenue(payment.getAmount(), payment.getCurrency());
        log.info("Payment succeeded", kv("event", "payment_succeeded"), kv("orderId", order.getId()),
                kv("paymentIntentId", intent.getId()), kv("amount", payment.getAmount()), kv("currency", payment.getCurrency()));

        // handled AFTER_COMMIT (e-mail + notification), exactly once because markPaid is a one-way transition
        events.publishEvent(new PaymentSucceededEvent(order.getId(), ownerId));
    }

    private void markFailed(Order order, Payment payment, String message) {
        if (isPaid(order) || PaymentStatus.FAILED.name().equals(payment.getStatus())) {
            return;
        }
        String shortMessage = message == null ? "Payment failed" : message.substring(0, Math.min(message.length(), 500));
        payment.setStatus(PaymentStatus.FAILED.name());
        payment.setFailureMessage(shortMessage);
        order.setPaymentStatus(PaymentStatus.FAILED);
        touch(order, payment);
        BusinessMetrics.payment("failed");
        log.warn("Payment failed", kv("event", "payment_failed"), kv("orderId", order.getId()), kv("reason", shortMessage));
    }

    /** The customer is retrying on the same PaymentIntent after a failure. */
    private void reopen(Order order, Payment payment) {
        if (PaymentStatus.FAILED.name().equals(payment.getStatus()) || PaymentStatus.CANCELED.name().equals(payment.getStatus())) {
            payment.setStatus(PaymentStatus.PENDING.name());
            payment.setFailureMessage(null);
            order.setPaymentStatus(PaymentStatus.PENDING);
            touch(order, payment);
        }
    }

    private void touch(Order order, Payment payment) {
        payment.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
        paymentRepo.save(payment);
    }

    // ------------------------------------------------------------------------------ helpers
    private Order lockOwnedOrder(Long orderId, User user) {
        Order order = orderRepo.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Order Not Found"));
        assertOwner(order, user);
        return order;
    }

    private void assertOwner(Order order, User user) {
        Long ownerId = ownerId(order);
        if (ownerId == null || !Objects.equals(ownerId, user.getId())) {
            // 404 rather than 403: do not reveal that someone else's order exists
            throw new NotFoundException("Order Not Found");
        }
    }

    private Long ownerId(Order order) {
        if (order.getUser() != null) {
            return order.getUser().getId();
        }
        // legacy orders created before Order.user existed: fall back to the line items' owner
        List<OrderItem> items = order.getOrderItemList();
        if (items != null) {
            return items.stream().map(OrderItem::getUser).filter(Objects::nonNull).map(User::getId)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
        return null;
    }

    private boolean isPaid(Order order) {
        return order.getPaymentStatus() == PaymentStatus.PAID;
    }

    /** Server-side amount: sum of the stored line prices. The client never decides what is charged. */
    BigDecimal payableTotal(Order order) {
        BigDecimal total = order.getOrderItemList() == null ? BigDecimal.ZERO : order.getOrderItemList().stream()
                .map(OrderItem::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "Order has nothing to pay");
        }
        return total;
    }

    static long toMinorUnits(BigDecimal amount, String currencyCode) {
        int digits = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
        return amount.movePointRight(Math.max(digits, 0)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Response intentResponse(Long orderId, PaymentIntent intent, boolean reused) {
        return Response.builder()
                .status(200)
                .paymentIntent(PaymentIntentDto.builder()
                        .orderId(orderId)
                        .paymentIntentId(intent.getId())
                        .clientSecret(intent.getClientSecret())
                        .amount(intent.getAmount())
                        .currency(intent.getCurrency())
                        .status(intent.getStatus())
                        .reused(reused)
                        .build())
                .build();
    }

    private Response statusResponse(Order order, Payment payment) {
        PaymentStatus status = order.getPaymentStatus() == null ? PaymentStatus.UNPAID : order.getPaymentStatus();
        List<OrderItemDto> items = order.getOrderItemList() == null ? List.of() : order.getOrderItemList().stream()
                .map(entityDtoMapper::mapOrderItemToDtoPlusProduct).toList();
        BigDecimal total = order.getOrderItemList() == null ? order.getTotalPrice() : order.getOrderItemList().stream()
                .map(OrderItem::getPrice).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentDto paymentDto = PaymentDto.builder()
                .id(payment == null ? null : payment.getId())
                .orderId(order.getId())
                .amount(total)
                .currency(payment == null ? properties.getPayment().getCurrency().toLowerCase() : payment.getCurrency())
                .status(status.name())
                .paymentIntentId(payment == null ? null : payment.getStripePaymentIntentId())
                .failureMessage(payment == null ? null : payment.getFailureMessage())
                .createdAt(payment == null ? null : payment.getCreatedAt())
                .paidAt(order.getPaidAt())
                .build();

        return Response.builder()
                .status(200)
                .payment(paymentDto)
                .order(new OrderDto(order.getId(), total, order.getCreatedAt(), items))
                .build();
    }
}
