package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.BudgetSummaryResponse;
import com.mannschaft.app.budget.dto.CategorySummaryResponse;
import com.mannschaft.app.budget.service.BudgetSummaryService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 予算サマリーコントローラー。
 * 会計年度・カテゴリ単位の予算サマリー取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget/fiscal-years/{fiscalYearId}")
@RequiredArgsConstructor
public class BudgetSummaryController {

    private final BudgetSummaryService budgetSummaryService;

    /**
     * 会計年度の予算サマリーを取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @param month 対象月（任意、指定時は月別サマリー）
     * @return 予算サマリー
     */
    @GetMapping("/summary")
    public ApiResponse<BudgetSummaryResponse> getFiscalYearSummary(
            @PathVariable Long fiscalYearId,
            @RequestParam(required = false) Integer month) {
        return ApiResponse.of(budgetSummaryService.getFiscalYearSummary(fiscalYearId));
    }

    /**
     * カテゴリ別の予算サマリーを取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @param categoryId カテゴリID
     * @return カテゴリ別予算サマリー
     */
    @GetMapping("/categories/{categoryId}/summary")
    public ApiResponse<CategorySummaryResponse> getCategorySummary(
            @PathVariable Long fiscalYearId,
            @PathVariable Long categoryId) {
        return ApiResponse.of(budgetSummaryService.getCategorySummary(categoryId, fiscalYearId));
    }
}
