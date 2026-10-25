package com.phegondev.Phegon.Eccormerce.payment;

import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.exception.PaymentException;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeGatewayImpl implements StripeGateway {

    private final AppProperties properties;

    @Override
    public PaymentIntent createPaymentIntent(long amountMinorUnits, String currency, Map<String, String> metadata, String idempotencyKey) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(amountMinorUnits)
                .setCurrency(currency)
                // card payments only: keeps the flow redirect-free so confirmation stays in-page
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build());
        metadata.forEach(params::putMetadata);

        RequestOptions options = RequestOptions.builder()
                .setApiKey(secretKey())
                .setIdempotencyKey(idempotencyKey)
                .build();
        try {
            return PaymentIntent.create(params.build(), options);
        } catch (StripeException e) {
            throw providerError("create PaymentIntent", e);
        }
    }

    @Override
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) {
        try {
            return PaymentIntent.retrieve(paymentIntentId, RequestOptions.builder().setApiKey(secretKey()).build());
        } catch (StripeException e) {
            throw providerError("retrieve PaymentIntent", e);
        }
    }

    @Override
    public Event verifyWebhook(String payload, String signatureHeader) {
        String secret = properties.getPayment().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe webhook is not configured (STRIPE_WEBHOOK_SECRET)");
        }
        try {
            return Webhook.constructEvent(payload, signatureHeader, secret);
        } catch (SignatureVerificationException e) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "Invalid Stripe signature", e);
        }
    }

    @Override
    public PaymentIntent extractPaymentIntent(Event event) {
        try {
            // deserializeUnsafe: tolerant of Stripe API-version differences between the account and the SDK
            StripeObject object = event.getDataObjectDeserializer().deserializeUnsafe();
            return object instanceof PaymentIntent intent ? intent : null;
        } catch (EventDataObjectDeserializationException e) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "Could not read Stripe event payload", e);
        }
    }

    private String secretKey() {
        String key = properties.getPayment().getSecretKey();
        if (key == null || key.isBlank()) {
            throw new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not configured (STRIPE_SECRET_KEY is empty)");
        }
        return key;
    }

    private PaymentException providerError(String action, StripeException e) {
        log.error("Stripe call failed: {} (code={}, requestId={})", action, e.getCode(), e.getRequestId());
        return new PaymentException(HttpStatus.BAD_GATEWAY, "Payment provider error while trying to " + action, e);
    }
}
