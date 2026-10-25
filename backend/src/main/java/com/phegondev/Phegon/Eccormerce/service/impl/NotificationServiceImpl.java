package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.dto.NotificationDto;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.entity.Notification;
import com.phegondev.Phegon.Eccormerce.entity.Order;
import com.phegondev.Phegon.Eccormerce.entity.OrderItem;
import com.phegondev.Phegon.Eccormerce.entity.Payment;
import com.phegondev.Phegon.Eccormerce.entity.User;
import com.phegondev.Phegon.Eccormerce.enums.NotificationChannel;
import com.phegondev.Phegon.Eccormerce.enums.NotificationStatus;
import com.phegondev.Phegon.Eccormerce.enums.NotificationType;
import com.phegondev.Phegon.Eccormerce.exception.NotFoundException;
import com.phegondev.Phegon.Eccormerce.repository.NotificationRepo;
import com.phegondev.Phegon.Eccormerce.repository.OrderRepo;
import com.phegondev.Phegon.Eccormerce.repository.PaymentRepo;
import com.phegondev.Phegon.Eccormerce.service.interf.NotificationService;
import com.phegondev.Phegon.Eccormerce.service.interf.UserService;
import com.phegondev.Phegon.Eccormerce.telemetry.BusinessMetrics;
import com.phegondev.Phegon.Eccormerce.telemetry.Tracing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    static final String TEMPLATE = "email/payment-success";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH);

    private final NotificationRepo notificationRepo;
    private final OrderRepo orderRepo;
    private final PaymentRepo paymentRepo;
    private final UserService userService;
    private final EmailService emailService;
    private final AppProperties properties;

    // ------------------------------------------------------------------ payment success e-mail
    @Override
    @Transactional
    public void sendPaymentSuccess(Long orderId) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Cannot notify: order not found", kv("orderId", orderId));
            return;
        }
        User user = recipientOf(order);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Cannot notify: order has no recipient e-mail", kv("orderId", orderId));
            return;
        }
        // second line of defence (the first is the one-way PAID transition): never e-mail twice
        if (notificationRepo.existsByOrderIdAndTypeAndChannel(orderId, NotificationType.PAYMENT_SUCCESS, NotificationChannel.EMAIL)) {
            log.info("Payment e-mail already recorded, skipping", kv("orderId", orderId));
            return;
        }

        Payment payment = paymentRepo.findByOrderId(orderId).orElse(null);
        String currency = payment != null && payment.getCurrency() != null ? payment.getCurrency() : properties.getPayment().getCurrency();
        Map<String, Object> model = buildModel(order, user, payment, currency);
        String subject = "Payment received - order #" + orderId;

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(NotificationType.PAYMENT_SUCCESS);
        notification.setChannel(NotificationChannel.EMAIL);
        notification.setRecipient(user.getEmail());
        notification.setSubject(subject);
        notification.setMessage("We received your payment of " + model.get("total") + " for order #" + orderId + ".");
        notification.setOrderId(orderId);
        notification.setAttempts(1);

        try {
            Tracing.runInSpan("notification.send_email", Map.of("order.id", String.valueOf(orderId), "notification.type", "PAYMENT_SUCCESS"),
                    () -> emailService.sendHtml(user.getEmail(), subject, TEMPLATE, model, plainText(model)));
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            BusinessMetrics.notification("email", "sent");
            log.info("Payment e-mail sent", kv("event", "email_sent"), kv("orderId", orderId), kv("recipient", user.getEmail()));
        } catch (RuntimeException e) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setErrorMessage(truncate(e.getMessage(), 480));
            BusinessMetrics.notification("email", "failed");
            log.error("Payment e-mail failed", kv("event", "email_failed"), kv("orderId", orderId), kv("recipient", user.getEmail()), e);
        }
        notificationRepo.save(notification);
    }

    // ------------------------------------------------------------------ inbox API
    @Override
    @Transactional(readOnly = true)
    public Response getMyNotifications() {
        User user = userService.getLoginUser();
        List<NotificationDto> list = notificationRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(this::toDto).toList();
        return Response.builder().status(200).notificationList(list).build();
    }

    @Override
    @Transactional(readOnly = true)
    public Response getMyUnreadCount() {
        User user = userService.getLoginUser();
        return Response.builder().status(200).unreadCount(notificationRepo.countByUserIdAndReadFalse(user.getId())).build();
    }

    @Override
    @Transactional
    public Response markAsRead(Long notificationId) {
        User user = userService.getLoginUser();
        Notification notification = notificationRepo.findById(notificationId)
                .filter(n -> n.getUser() != null && Objects.equals(n.getUser().getId(), user.getId()))
                .orElseThrow(() -> new NotFoundException("Notification Not Found"));
        notification.setRead(true);
        notificationRepo.save(notification);
        return Response.builder().status(200).message("Notification marked as read").notification(toDto(notification)).build();
    }

    @Override
    @Transactional(readOnly = true)
    public Response getAllNotifications(Pageable pageable) {
        Page<Notification> page = notificationRepo.findAllByOrderByCreatedAtDesc(pageable);
        return Response.builder()
                .status(200)
                .notificationList(page.getContent().stream().map(this::toDto).toList())
                .totalPage(page.getTotalPages())
                .totalElement(page.getTotalElements())
                .build();
    }

    // ------------------------------------------------------------------ e-mail model
    Map<String, Object> buildModel(Order order, User user, Payment payment, String currency) {
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        try {
            money.setCurrency(Currency.getInstance(currency.toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            // unknown currency code: keep default symbol
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        if (order.getOrderItemList() != null) {
            for (OrderItem item : order.getOrderItemList()) {
                BigDecimal line = item.getPrice() == null ? BigDecimal.ZERO : item.getPrice();
                BigDecimal unit = item.getQuantity() > 0
                        ? line.divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP) : line;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", item.getProduct() != null ? item.getProduct().getName() : "Item");
                row.put("imageUrl", item.getProduct() != null ? item.getProduct().getImageUrl() : null);
                row.put("quantity", item.getQuantity());
                row.put("unitPrice", money.format(unit));
                row.put("lineTotal", money.format(line));
                items.add(row);
                total = total.add(line);
            }
        }
        if (payment != null && payment.getAmount() != null) {
            total = payment.getAmount();
        }
        LocalDateTime paidAt = order.getPaidAt() != null ? order.getPaidAt() : LocalDateTime.now();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("brandName", properties.getMail().getBrandName());
        model.put("customerName", user.getName() == null ? "there" : user.getName());
        model.put("orderId", order.getId());
        model.put("paidAt", DATE_FORMAT.format(paidAt.atOffset(ZoneOffset.UTC)));
        model.put("items", items);
        model.put("total", money.format(total));
        model.put("currency", currency.toUpperCase());
        model.put("paymentReference", payment != null ? payment.getStripePaymentIntentId() : null);
        model.put("orderUrl", properties.getMail().getFrontendUrl() + "/profile");
        model.put("supportEmail", properties.getMail().getSupportEmail());
        model.put("year", LocalDateTime.now().getYear());
        return model;
    }

    @SuppressWarnings("unchecked")
    static String plainText(Map<String, Object> model) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(model.get("customerName")).append(",\n\n")
                .append("Thank you! We received your payment for order #").append(model.get("orderId"))
                .append(" on ").append(model.get("paidAt")).append(".\n\n");
        for (Map<String, Object> item : (List<Map<String, Object>>) model.get("items")) {
            sb.append("- ").append(item.get("name")).append(" x").append(item.get("quantity"))
                    .append("  ").append(item.get("lineTotal")).append('\n');
        }
        sb.append("\nTotal paid: ").append(model.get("total")).append("\n\n")
                .append("View your orders: ").append(model.get("orderUrl")).append("\n")
                .append("Questions? ").append(model.get("supportEmail")).append("\n\n")
                .append("- The ").append(model.get("brandName")).append(" team\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers
    private User recipientOf(Order order) {
        if (order.getUser() != null) {
            return order.getUser();
        }
        return order.getOrderItemList() == null ? null : order.getOrderItemList().stream()
                .map(OrderItem::getUser).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType().name())
                .channel(n.getChannel().name())
                .status(n.getStatus().name())
                .subject(n.getSubject())
                .message(n.getMessage())
                .orderId(n.getOrderId())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .sentAt(n.getSentAt())
                .build();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
