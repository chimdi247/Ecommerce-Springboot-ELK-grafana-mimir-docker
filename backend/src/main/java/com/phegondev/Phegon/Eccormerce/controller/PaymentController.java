package com.phegondev.Phegon.Eccormerce.controller;

import com.phegondev.Phegon.Eccormerce.dto.PaymentIntentRequest;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.service.interf.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** Public: the browser needs the publishable key to initialise Stripe.js. */
    @GetMapping("/config")
    public ResponseEntity<Response> config() {
        return ResponseEntity.ok(paymentService.getConfig());
    }

    /**
     * Creates the PaymentIntent for an order. The {@code Idempotency-Key} header (client generated
     * UUID, stable for the checkout session) makes retries safe: the same key returns the same
     * PaymentIntent and an already paid order answers 409.
     */
    @PostMapping("/create-intent")
    public ResponseEntity<Response> createIntent(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentIntentRequest request) {
        return ResponseEntity.ok(paymentService.createPaymentIntent(request.getOrderId(), idempotencyKey));
    }

    /** Called by the success page: verifies the PaymentIntent with Stripe and finalizes the order. */
    @PostMapping("/confirm/{orderId}")
    public ResponseEntity<Response> confirm(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.confirmPayment(orderId));
    }

    @GetMapping("/status/{orderId}")
    public ResponseEntity<Response> status(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.getPaymentStatus(orderId));
    }

    /** Stripe -> backend. Public route, authenticated by the Stripe-Signature header. */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader("Stripe-Signature") String signature) {
        paymentService.handleWebhook(payload, signature);
        return ResponseEntity.ok("received");
    }
}
