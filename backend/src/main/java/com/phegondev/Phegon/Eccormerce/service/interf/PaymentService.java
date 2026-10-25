package com.phegondev.Phegon.Eccormerce.service.interf;

import com.phegondev.Phegon.Eccormerce.dto.Response;

public interface PaymentService {

    Response getConfig();

    /** Creates (or idempotently returns) the PaymentIntent for an order. */
    Response createPaymentIntent(Long orderId, String idempotencyKey);

    /** Server-side verification with Stripe; marks the order paid when Stripe says so. */
    Response confirmPayment(Long orderId);

    Response getPaymentStatus(Long orderId);

    /** Stripe webhook entry point (signature verified, de-duplicated by event id). */
    void handleWebhook(String payload, String signatureHeader);
}
