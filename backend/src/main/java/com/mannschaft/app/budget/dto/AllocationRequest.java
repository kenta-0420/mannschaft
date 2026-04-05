package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 予算配分リクエスト。
 */
public record AllocationRequest(

        @NotNull
        Long categoryId,

        @NotNull
        Integer month,

        @NotNull
        @DecimalMin(value = "0")
        BigDecimal amount
) {
}
