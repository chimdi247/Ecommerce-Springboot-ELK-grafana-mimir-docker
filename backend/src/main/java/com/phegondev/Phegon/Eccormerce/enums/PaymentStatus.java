package com.phegondev.Phegon.Eccormerce.enums;

/** Lifecycle shared by {@code Order.paymentStatus} and {@code Payment.status}. */
public enum PaymentStatus {
    /** Order created, no payment started. */
    UNPAID,
    /** A Stripe PaymentIntent exists and is awaiting confirmation. */
    PENDING,
    /** Stripe confirmed the charge. Terminal: an order can never leave this state. */
    PAID,
    /** The last payment attempt failed; the customer may retry on the same PaymentIntent. */
    FAILED,
    /** The PaymentIntent was canceled. */
    CANCELED
}
