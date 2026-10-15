package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.CreateFiscalYearRequest;
import com.mannschaft.app.budget.dto.FiscalYearResponse;
import com.mannschaft.app.budget.service.BudgetFiscalYearService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会計年度コントローラー。
 * 会計年度の取得・更新・締め・再開・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget/fiscal-years")
@RequiredArgsConstructor
public class BudgetFiscalYearController {

    private final BudgetFiscalYearService budgetFiscalYearService;

    /**
     * 会計年度を取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @return 会計年度
     */
    @GetMapping("/{fiscalYearId}")
    public ApiResponse<FiscalYearResponse> getById(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetFiscalYearService.getById(fiscalYearId));
    }

    /**
     * 会計年度を更新する。
     *
     * @param fiscalYearId 会計年度ID
     * @param request 更新リクエスト
     * @return 更新後の会計年度
     */
    @PatchMapping("/{fiscalYearId}")
    public ApiResponse<FiscalYearResponse> update(
            @PathVariable Long fiscalYearId,
            @Valid @RequestBody CreateFiscalYearRequest request) {
        return ApiResponse.of(budgetFiscalYearService.update(fiscalYearId, request));
    }

    /**
     * 会計年度を締める。
     *
     * @param fiscalYearId 会計年度ID
     * @return 締め後の会計年度
     */
    @PostMapping("/{fiscalYearId}/close")
    public ApiResponse<FiscalYearResponse> close(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetFiscalYearService.close(fiscalYearId));
    }

    /**
     * 会計年度を再開する。
     *
     * @param fiscalYearId 会計年度ID
     * @return 再開後の会計年度
     */
    @PostMapping("/{fiscalYearId}/reopen")
    public ApiResponse<FiscalYearResponse> reopen(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetFiscalYearService.reopen(fiscalYearId));
    }

    /**
     * 会計年度を削除する。
     *
     * @param fiscalYearId 会計年度ID
     */
    @DeleteMapping("/{fiscalYearId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long fiscalYearId) {
        budgetFiscalYearService.delete(fiscalYearId);
    }
}
