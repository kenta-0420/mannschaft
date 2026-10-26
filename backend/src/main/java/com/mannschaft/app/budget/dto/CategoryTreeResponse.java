package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 予算カテゴリツリーレスポンス。子カテゴリを再帰的に保持する。
 */
public record CategoryTreeResponse(
        Long id,
        Long fiscalYearId,
        String name,
        String categoryType,
        Long parentId,
        Integer sortOrder,
        String description,
        BigDecimal budgetAmount,
        List<CategoryTreeResponse> children
) {
}
