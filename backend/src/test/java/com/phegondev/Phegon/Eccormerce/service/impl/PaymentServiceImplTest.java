package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.dto.Response;
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
import com.phegondev.Phegon.Eccormerce.service.interf.UserService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    private static final String KEY = "checkout-key-12345678";
    private static final String SCOPED_KEY = "order-10-" + KEY;

    @Mock private OrderRepo orderRepo;
    @Mock private PaymentRepo paymentRepo;
    @Mock private ProcessedStripeEventRepo processedEventRepo;
    @Mock private CartRepo cartRepo;
    @Mock private UserService userService;
    @Mock private StripeGateway stripe;
    @Mock private EntityDtoMapper entityDtoMapper;
    @Mock private ApplicationEventPublisher events;

    private PaymentServiceImpl service;
    private User user;
    private Order order;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setCurrency("usd");
        service = new PaymentServiceImpl(orderRepo, paymentRepo, processedEventRepo, cartRepo, userService,
                stripe, entityDtoMapper, events, properties);

        user = new User();
        user.setId(1L);
        user.setEmail("buyer@example.com");
        when(userService.getLoginUser()).thenReturn(user);

        order = new Order();
        order.setId(10L);
        order.setUser(user);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        order.setTotalPrice(new BigDecimal("50.00"));
        order.setOrderItemList(new ArrayList<>(List.of(item("20.00"), item("30.00"))));
        when(orderRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.empty());
        when(processedEventRepo.existsById(anyString())).thenReturn(false);
    }

    // ------------------------------------------------------------------ create intent
    @Test
    void createsIntentWithServerSideAmountAndOrderScopedIdempotencyKey() {
        when(stripe.createPaymentIntent(eq(5000L), eq("usd"), anyMap(), eq(SCOPED_KEY)))
                .thenReturn(intent("pi_1", "requires_payment_method"));

        Response response = service.createPaymentIntent(10L, KEY);

        assertEquals("pi_1", response.getPaymentIntent().getPaymentIntentId());
        assertEquals("secret_pi_1", response.getPaymentIntent().getClientSecret());
        assertFalse(response.getPaymentIntent().isReused());

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo).save(saved.capture());
        assertEquals(SCOPED_KEY, saved.getValue().getIdempotencyKey());
        assertEquals("PENDING", saved.getValue().getStatus());
        assertEquals(PaymentStatus.PENDING, order.getPaymentStatus());
    }

    @Test
    void sameIdempotencyKeyReplaysTheExistingIntentWithoutCreatingAnother() {
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method"));

        Response response = service.createPaymentIntent(10L, KEY);

        assertTrue(response.getPaymentIntent().isReused());
        assertEquals("pi_1", response.getPaymentIntent().getPaymentIntentId());
        verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void differentKeyForAnOrderWithAnOpenIntentReusesItInsteadOfCreatingASecondPayment() {
        Payment existing = payment("pi_1", "order-10-some-older-key-0000", PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_action"));

        Response response = service.createPaymentIntent(10L, KEY);

        assertTrue(response.getPaymentIntent().isReused());
        verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void canceledIntentIsReplacedByANewOne() {
        Payment existing = payment("pi_old", "order-10-old-key-123456", PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled"));
        when(stripe.createPaymentIntent(eq(5000L), eq("usd"), anyMap(), eq(SCOPED_KEY)))
                .thenReturn(intent("pi_new", "requires_payment_method"));

        Response response = service.createPaymentIntent(10L, KEY);

        assertEquals("pi_new", response.getPaymentIntent().getPaymentIntentId());
        assertEquals("pi_new", existing.getStripePaymentIntentId());   // same row: still one payment per order
    }

    @Test
    void anOrderThatIsAlreadyPaidCanNeverCreateAnotherIntent() {
        order.setPaymentStatus(PaymentStatus.PAID);

        PaymentException ex = assertThrows(PaymentException.class, () -> service.createPaymentIntent(10L, KEY));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verifyNoInteractions(stripe);
    }

    @Test
    void ifStripeAlreadySucceededButWeMissedItTheOrderIsHealedAndTheRetryIsRejected() {
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "succeeded"));

        PaymentException ex = assertThrows(PaymentException.class, () -> service.createPaymentIntent(10L, KEY));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(events).publishEvent(any(PaymentSucceededEvent.class));
        verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void anotherUsersOrderLooksLikeItDoesNotExist() {
        User other = new User();
        other.setId(99L);
        when(userService.getLoginUser()).thenReturn(other);

        assertThrows(NotFoundException.class, () -> service.createPaymentIntent(10L, KEY));
        verifyNoInteractions(stripe);
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsRejected() {
        assertThrows(InvalidCredentialsException.class, () -> service.createPaymentIntent(10L, null));
        assertThrows(InvalidCredentialsException.class, () -> service.createPaymentIntent(10L, "short"));
        assertThrows(InvalidCredentialsException.class, () -> service.createPaymentIntent(10L, "has spaces and !! chars"));
        verifyNoInteractions(stripe);
    }

    @Test
    void emptyOrderCannotBePaid() {
        order.setOrderItemList(new ArrayList<>());
        PaymentException ex = assertThrows(PaymentException.class, () -> service.createPaymentIntent(10L, KEY));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ------------------------------------------------------------------ confirm
    @Test
    void confirmMarksOrderPaidOnceAndPublishesExactlyOneEvent() {
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "succeeded"));

        Response first = service.confirmPayment(10L);
        Response second = service.confirmPayment(10L);   // e.g. page refresh / double click

        assertEquals("PAID", first.getPayment().getStatus());
        assertEquals("PAID", second.getPayment().getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertNotNull(order.getPaidAt());
        assertTrue(order.getOrderItemList().stream().allMatch(i -> i.getStatus() == OrderStatus.CONFIRMED));
        verify(events, times(1)).publishEvent(any(PaymentSucceededEvent.class));
        verify(stripe, times(1)).retrievePaymentIntent("pi_1");   // second call short-circuits
    }

    @Test
    void confirmKeepsOrderPendingWhileStripeIsStillProcessing() {
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PENDING);
        when(paymentRepo.findByOrderId(10L)).thenReturn(Optional.of(existing));
        when(stripe.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "processing"));

        order.setPaymentStatus(PaymentStatus.PENDING);

        Response response = service.confirmPayment(10L);

        assertEquals("PENDING", response.getPayment().getStatus());
        assertNotEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(events, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------ webhook
    @Test
    void duplicateWebhookEventsAreIgnored() {
        Event event = new Event();
        event.setId("evt_1");
        event.setType("payment_intent.succeeded");
        when(stripe.verifyWebhook("{}", "sig")).thenReturn(event);
        when(processedEventRepo.existsById("evt_1")).thenReturn(true);

        service.handleWebhook("{}", "sig");

        verify(stripe, never()).extractPaymentIntent(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void webhookSucceededFinalizesTheOrderAndRecordsTheEvent() {
        Event event = new Event();
        event.setId("evt_2");
        event.setType("payment_intent.succeeded");
        when(stripe.verifyWebhook("{}", "sig")).thenReturn(event);
        PaymentIntent succeeded = intent("pi_1", "succeeded");
        when(stripe.extractPaymentIntent(event)).thenReturn(succeeded);
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PENDING);
        existing.setOrder(order);
        when(paymentRepo.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(existing));

        service.handleWebhook("{}", "sig");

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(events, times(1)).publishEvent(any(PaymentSucceededEvent.class));
        ArgumentCaptor<ProcessedStripeEvent> recorded = ArgumentCaptor.forClass(ProcessedStripeEvent.class);
        verify(processedEventRepo).save(recorded.capture());
        assertEquals("evt_2", recorded.getValue().getEventId());
    }

    @Test
    void aLateFailedEventNeverDowngradesAPaidOrder() {
        order.setPaymentStatus(PaymentStatus.PAID);
        Event event = new Event();
        event.setId("evt_3");
        event.setType("payment_intent.payment_failed");
        when(stripe.verifyWebhook("{}", "sig")).thenReturn(event);
        when(stripe.extractPaymentIntent(event)).thenReturn(intent("pi_1", "requires_payment_method"));
        Payment existing = payment("pi_1", SCOPED_KEY, PaymentStatus.PAID);
        existing.setOrder(order);
        when(paymentRepo.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(existing));

        service.handleWebhook("{}", "sig");

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
    }

    // ------------------------------------------------------------------ amounts
    @Test
    void convertsToMinorUnitsUsingTheCurrencyFractionDigits() {
        assertEquals(1999L, PaymentServiceImpl.toMinorUnits(new BigDecimal("19.99"), "usd"));
        assertEquals(500L, PaymentServiceImpl.toMinorUnits(new BigDecimal("500"), "jpy"));   // zero-decimal currency
        assertEquals(1L, PaymentServiceImpl.toMinorUnits(new BigDecimal("0.005"), "usd"));   // HALF_UP
    }

    // ------------------------------------------------------------------ fixtures
    private OrderItem item(String price) {
        OrderItem item = new OrderItem();
        item.setPrice(new BigDecimal(price));
        item.setQuantity(1);
        item.setStatus(OrderStatus.PENDING);
        item.setUser(user);
        return item;
    }

    private Payment payment(String intentId, String key, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStripePaymentIntentId(intentId);
        payment.setIdempotencyKey(key);
        payment.setStatus(status.name());
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCurrency("usd");
        return payment;
    }

    private PaymentIntent intent(String id, String status) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setStatus(status);
        intent.setClientSecret("secret_" + id);
        intent.setAmount(5000L);
        intent.setCurrency("usd");
        return intent;
    }
}
