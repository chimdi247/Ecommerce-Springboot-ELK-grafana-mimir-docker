package com.phegondev.Phegon.Eccormerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentIntentDto {
    private Long orderId;
    private String paymentIntentId;
    /** Used by Stripe.js in the browser to confirm the payment. */
    private String clientSecret;
    /** Amount in the currency's minor unit (cents). */
    private Long amount;
    private String currency;
    private String status;
    /** true when an existing PaymentIntent was returned instead of creating a new one. */
    private boolean reused;
}
