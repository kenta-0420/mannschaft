package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.CategoryResponse;
import com.mannschaft.app.budget.dto.CategoryTreeResponse;
import com.mannschaft.app.budget.dto.CreateCategoryRequest;
import com.mannschaft.app.budget.dto.UpdateCategoryRequest;
import com.mannschaft.app.budget.service.BudgetCategoryService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 予算カテゴリコントローラー。
 * カテゴリの一覧取得・作成・更新・削除・前年度コピーAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget")
@RequiredArgsConstructor
public class BudgetCategoryController {

    private final BudgetCategoryService budgetCategoryService;

    /**
     * 会計年度に紐づくカテゴリ一覧をツリー形式で取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @return カテゴリツリー一覧
     */
    @GetMapping("/fiscal-years/{fiscalYearId}/categories")
    public ApiResponse<List<CategoryTreeResponse>> listByFiscalYear(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetCategoryService.listByFiscalYear(fiscalYearId));
    }

    /**
     * 会計年度にカテゴリを作成する。
     *
     * @param fiscalYearId 会計年度ID
     * @param scopeId スコープID
     * @param scopeType スコープ種別
     * @param request 作成リクエスト
     * @return 作成されたカテゴリ
     */
    @PostMapping("/fiscal-years/{fiscalYearId}/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CategoryResponse> create(
            @PathVariable Long fiscalYearId,
            @RequestParam Long scopeId,
            @RequestParam String scopeType,
            @Valid @RequestBody CreateCategoryRequest request) {
        return ApiResponse.of(budgetCategoryService.create(request, scopeId, scopeType));
    }

    /**
     * カテゴリを更新する。
     *
     * @param categoryId カテゴリID
     * @param scopeId スコープID
     * @param scopeType スコープ種別
     * @param request 更新リクエスト
     * @return 更新後のカテゴリ
     */
    @PatchMapping("/categories/{categoryId}")
    public ApiResponse<CategoryResponse> update(
            @PathVariable Long categoryId,
            @RequestParam Long scopeId,
            @RequestParam String scopeType,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return ApiResponse.of(budgetCategoryService.update(categoryId, request, scopeId, scopeType));
    }

    /**
     * カテゴリを削除する。
     *
     * @param categoryId カテゴリID
     * @param scopeId スコープID
     * @param scopeType スコープ種別
     */
    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long categoryId,
            @RequestParam Long scopeId,
            @RequestParam String scopeType) {
        budgetCategoryService.delete(categoryId, scopeId, scopeType);
    }

    /**
     * 前年度のカテゴリ構成をコピーする。
     *
     * @param fiscalYearId コピー先の会計年度ID
     * @param sourceFiscalYearId コピー元の会計年度ID
     */
    @PostMapping("/fiscal-years/{fiscalYearId}/categories/copy-from/{sourceFiscalYearId}")
    @ResponseStatus(HttpStatus.CREATED)
    public void copyFromPreviousYear(
            @PathVariable Long fiscalYearId,
            @PathVariable Long sourceFiscalYearId) {
        budgetCategoryService.copyFromPreviousYear(sourceFiscalYearId, fiscalYearId);
    }
}
