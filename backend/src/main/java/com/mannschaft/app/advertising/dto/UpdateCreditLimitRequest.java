package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 与信限度額更新リクエスト。
 */
public record UpdateCreditLimitRequest(

        @NotNull
        @DecimalMin(value = "1", inclusive = true)
        BigDecimal creditLimit
) {
}
