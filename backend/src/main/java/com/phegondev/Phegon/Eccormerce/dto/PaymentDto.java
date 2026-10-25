package com.phegondev.Phegon.Eccormerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentDto {
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String currency;
    /** UNPAID | PENDING | PAID | FAILED | CANCELED */
    private String status;
    private String paymentIntentId;
    private String failureMessage;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
}
