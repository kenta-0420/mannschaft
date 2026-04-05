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
 * チームスコープの予算設定コントローラー。
 * チームの予算設定取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/budget/config")
@RequiredArgsConstructor
public class TeamBudgetConfigController {

    private final BudgetConfigService budgetConfigService;
    private final AccessControlService accessControlService;

    /**
     * チームの予算設定を取得する。
     *
     * @param teamId チームID
     * @return 予算設定
     */
    @GetMapping
    public ApiResponse<BudgetConfigResponse> get(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(budgetConfigService.getByScope("TEAM", teamId));
    }

    /**
     * チームの予算設定を更新する。
     *
     * @param teamId チームID
     * @param request 更新リクエスト
     * @return 更新後の予算設定
     */
    @PatchMapping
    public ApiResponse<BudgetConfigResponse> update(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateBudgetConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(budgetConfigService.update("TEAM", teamId, request));
    }
}
