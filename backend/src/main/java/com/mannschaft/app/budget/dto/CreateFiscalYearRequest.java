package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 会計年度作成リクエスト。
 */
public record CreateFiscalYearRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        LocalDate startDate,

        @NotNull
        LocalDate endDate,

        @NotNull
        Long scopeId,

        @NotBlank
        String scopeType
) {
}
