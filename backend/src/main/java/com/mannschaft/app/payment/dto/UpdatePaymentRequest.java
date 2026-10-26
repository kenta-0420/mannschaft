package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 支払い記録修正リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePaymentRequest {

    @DecimalMin(value = "0.01")
    private final BigDecimal amountPaid;

    private final LocalDate validFrom;

    private final LocalDate validUntil;

    @Size(max = 500)
    private final String note;
}
