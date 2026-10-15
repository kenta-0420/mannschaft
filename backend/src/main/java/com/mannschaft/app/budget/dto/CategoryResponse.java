package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 予算カテゴリレスポンス。
 */
public record CategoryResponse(
        Long id,
        Long fiscalYearId,
        String name,
        String categoryType,
        Long parentId,
        Integer sortOrder,
        String description,
        BigDecimal budgetAmount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
