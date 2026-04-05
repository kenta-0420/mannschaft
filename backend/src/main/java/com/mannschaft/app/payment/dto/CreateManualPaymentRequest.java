package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 手動支払い記録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateManualPaymentRequest {

    @NotNull
    private final Long userId;

    @NotNull
    @DecimalMin(value = "0.01")
    private final BigDecimal amountPaid;

    @NotNull
    private final LocalDateTime paidAt;

    private final LocalDate validFrom;

    private final LocalDate validUntil;

    @Size(max = 500)
    private final String note;
}
