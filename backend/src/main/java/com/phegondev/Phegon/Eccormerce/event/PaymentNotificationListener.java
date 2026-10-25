package com.phegondev.Phegon.Eccormerce.event;

import com.phegondev.Phegon.Eccormerce.service.interf.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the confirmation e-mail only after the payment transaction has COMMITTED (so we never
 * e-mail about a payment that was rolled back) and off the request thread (SMTP is slow).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentNotificationListener {

    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        try {
            notificationService.sendPaymentSuccess(event.orderId());
        } catch (RuntimeException e) {
            log.error("Failed to process payment notification for order {}", event.orderId(), e);
        }
    }
}
