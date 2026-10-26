package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.BudgetConfigResponse;
import com.mannschaft.app.budget.dto.UpdateBudgetConfigRequest;
import com.mannschaft.app.budget.service.BudgetConfigService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織スコープの予算設定コントローラー。
 * 組織の予算設定取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/budget/config")
@RequiredArgsConstructor
public class OrgBudgetConfigController {

    private final BudgetConfigService budgetConfigService;
    private final AccessControlService accessControlService;

    /**
     * 組織の予算設定を取得する。
     *
     * @param orgId 組織ID
     * @return 予算設定
     */
    @GetMapping
    public ApiResponse<BudgetConfigResponse> get(@PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        return ApiResponse.of(budgetConfigService.getByScope("ORGANIZATION", orgId));
    }

    /**
     * 組織の予算設定を更新する。
     *
     * @param orgId 組織ID
     * @param request 更新リクエスト
     * @return 更新後の予算設定
     */
    @PatchMapping
    public ApiResponse<BudgetConfigResponse> update(
            @PathVariable Long orgId,
            @Valid @RequestBody UpdateBudgetConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        return ApiResponse.of(budgetConfigService.update("ORGANIZATION", orgId, request));
    }
}
