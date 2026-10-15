package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;

/**
 * カテゴリ別予算サマリレスポンス。消化率を含む。
 */
public record CategorySummaryResponse(
        Long categoryId,
        String categoryName,
        String categoryType,
        BigDecimal budgetAmount,
        BigDecimal actualAmount,
        BigDecimal remainingAmount,
        BigDecimal executionRate,
        String warningLevel
) {
}
