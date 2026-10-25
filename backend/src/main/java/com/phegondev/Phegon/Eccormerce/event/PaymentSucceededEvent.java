package com.phegondev.Phegon.Eccormerce.event;

/** Published inside the payment transaction; handled only AFTER it commits. */
public record PaymentSucceededEvent(Long orderId, Long userId) {
}
