package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * チーム・組織スコープの会計年度作成リクエスト。
 * 保存先スコープは URL から解決するため、本文では受け取らない。
 */
public record ScopedCreateFiscalYearRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        LocalDate startDate,

        @NotNull
        LocalDate endDate
) {
}
