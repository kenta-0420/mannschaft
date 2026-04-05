package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.AllocationResponse;
import com.mannschaft.app.budget.dto.BulkAllocationRequest;
import com.mannschaft.app.budget.dto.BulkAllocationResponse;
import com.mannschaft.app.budget.service.BudgetAllocationService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 予算配分コントローラー。
 * 会計年度単位での予算配分の一覧取得・一括更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget/fiscal-years/{fiscalYearId}/allocations")
@RequiredArgsConstructor
public class BudgetAllocationController {

    private final BudgetAllocationService budgetAllocationService;

    /**
     * 会計年度に紐づく予算配分一覧を取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @return 予算配分一覧
     */
    @GetMapping
    public ApiResponse<List<AllocationResponse>> listByFiscalYear(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetAllocationService.listByFiscalYear(fiscalYearId));
    }

    /**
     * 予算配分を一括で作成・更新する。
     *
     * @param fiscalYearId 会計年度ID
     * @param request 一括配分リクエスト
     * @return 一括配分結果
     */
    @PutMapping
    public ApiResponse<BulkAllocationResponse> bulkUpsert(
            @PathVariable Long fiscalYearId,
            @Valid @RequestBody BulkAllocationRequest request) {
        return ApiResponse.of(budgetAllocationService.bulkUpsert(request));
    }
}
