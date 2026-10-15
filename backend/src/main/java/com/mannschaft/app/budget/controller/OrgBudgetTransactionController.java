package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.TransactionResponse;
import com.mannschaft.app.budget.service.BudgetTransactionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織スコープの予算取引コントローラー。
 * 組織に紐づく取引一覧の検索・ページネーション取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/budget/transactions")
@RequiredArgsConstructor
public class OrgBudgetTransactionController {

    private final BudgetTransactionService budgetTransactionService;
    private final AccessControlService accessControlService;

    /**
     * 組織に紐づく取引一覧を取得する。
     *
     * @param orgId 組織ID
     * @param fiscalYearId 会計年度ID
     * @param pageable ページネーション情報
     * @return 取引一覧（ページネーション付き）
     */
    @GetMapping
    public PagedResponse<TransactionResponse> list(
            @PathVariable Long orgId,
            @RequestParam Long fiscalYearId,
            Pageable pageable) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        return budgetTransactionService.listByScope(fiscalYearId, pageable);
    }
}
