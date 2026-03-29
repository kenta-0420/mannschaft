package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会計年度レスポンス。
 */
public record FiscalYearResponse(
        Long id,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        Long scopeId,
        String scopeType,
        BigDecimal totalBudget,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
