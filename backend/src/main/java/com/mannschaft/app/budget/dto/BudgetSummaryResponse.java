package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会計年度予算サマリレスポンス。消化率を含む。
 */
public record BudgetSummaryResponse(
        Long fiscalYearId,
        String fiscalYearName,
        BigDecimal totalBudget,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        BigDecimal executionRate,
        String warningLevel,
        List<CategorySummaryResponse> categories
) {
}
