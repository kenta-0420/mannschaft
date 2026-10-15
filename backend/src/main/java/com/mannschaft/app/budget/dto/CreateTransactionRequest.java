package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 取引作成リクエスト。
 */
public record CreateTransactionRequest(

        @NotNull
        Long fiscalYearId,

        @NotNull
        Long categoryId,

        @NotBlank
        String transactionType,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal amount,

        @NotNull
        LocalDate transactionDate,

        @NotBlank
        @Size(max = 200)
        String description,

        String paymentMethod,

        @Size(max = 100)
        String reference,

        @Size(max = 2000)
        String memo
) {
}
