package com.phegondev.Phegon.Eccormerce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Public (browser-safe) Stripe configuration. Never contains the secret key. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfigDto {
    private String publishableKey;
    private String currency;
}
