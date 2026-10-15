package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.CreateFiscalYearRequest;
import com.mannschaft.app.budget.dto.FiscalYearResponse;
import com.mannschaft.app.budget.service.BudgetFiscalYearService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームスコープの会計年度コントローラー。
 * チームに紐づく会計年度の一覧取得・作成APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/budget/fiscal-years")
@RequiredArgsConstructor
public class TeamBudgetFiscalYearController {

    private final BudgetFiscalYearService budgetFiscalYearService;
    private final AccessControlService accessControlService;

    /**
     * チームに紐づく会計年度一覧を取得する。
     *
     * @param teamId チームID
     * @return 会計年度一覧
     */
    @GetMapping
    public ApiResponse<List<FiscalYearResponse>> list(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(budgetFiscalYearService.listByScope("TEAM", teamId));
    }

    /**
     * チームに紐づく会計年度を作成する。
     *
     * @param teamId チームID
     * @param request 作成リクエスト
     * @return 作成された会計年度
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FiscalYearResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFiscalYearRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(budgetFiscalYearService.create("TEAM", teamId, request));
    }
}
