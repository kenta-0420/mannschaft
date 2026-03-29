package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 報告書生成リクエスト。
 */
public record CreateReportRequest(

        @NotNull
        Long fiscalYearId,

        @NotBlank
        String reportType,

        @Size(max = 200)
        String title,

        Integer targetMonth
) {
}
