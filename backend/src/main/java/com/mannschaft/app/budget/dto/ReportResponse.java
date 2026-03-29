package com.mannschaft.app.budget.dto;

import java.time.LocalDateTime;

/**
 * 報告書レスポンス。
 */
public record ReportResponse(
        Long id,
        Long fiscalYearId,
        String reportType,
        String status,
        String title,
        Integer targetMonth,
        String s3Key,
        LocalDateTime generatedAt,
        LocalDateTime createdAt
) {
}
