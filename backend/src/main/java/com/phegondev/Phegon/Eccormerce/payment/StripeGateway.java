package com.phegondev.Phegon.Eccormerce.payment;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;

import java.util.Map;

/**
 * Thin seam over the Stripe SDK so the payment logic can be unit-tested without network access.
 * Implementations translate Stripe failures into {@code PaymentException}.
 */
public interface StripeGateway {

    /**
     * Creates a PaymentIntent. {@code idempotencyKey} is sent as Stripe's Idempotency-Key header: a
     * retry with the same key returns the original PaymentIntent instead of creating a second one.
     */
    PaymentIntent createPaymentIntent(long amountMinorUnits, String currency, Map<String, String> metadata, String idempotencyKey);

    PaymentIntent retrievePaymentIntent(String paymentIntentId);

    /** Verifies the {@code Stripe-Signature} header and parses the event. */
    Event verifyWebhook(String payload, String signatureHeader);

    PaymentIntent extractPaymentIntent(Event event);
}
