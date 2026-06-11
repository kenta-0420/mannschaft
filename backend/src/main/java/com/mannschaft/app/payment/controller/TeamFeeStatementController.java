package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.FeeStatementResponse;
import com.mannschaft.app.payment.service.FeeStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/**
 * F08.9 P8 月次手数料明細コントローラー。
 *
 * <p>チーム ADMIN が当月（または指定月）の Mannschaft 名義手数料明細を取得する。
 * 認可（チーム ADMIN 限定）・IDOR（他チームの閲覧防止）はサービス層 {@link FeeStatementService} と
 * {@link AccessControlService} で担保する。</p>
 *
 * <p>エンドポイント数: 1（GET fee-statements）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §8.2</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "月次手数料明細", description = "F08.9 P8 Mannschaft 名義の月次手数料明細")
@RequiredArgsConstructor
public class TeamFeeStatementController {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final FeeStatementService feeStatementService;
    private final AccessControlService accessControlService;

    /**
     * チームの月次手数料明細を取得する。
     *
     * @param teamId チーム ID
     * @param period 集計対象月（形式: {@code YYYY-MM}）。省略時は当月
     * @return 手数料明細レスポンス
     */
    @GetMapping("/fee-statements")
    @Operation(summary = "月次手数料明細（Mannschaft 名義）")
    public ResponseEntity<ApiResponse<FeeStatementResponse>> getFeeStatement(
            @PathVariable Long teamId,
            @RequestParam(required = false) String period) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, teamId, SCOPE_TYPE_TEAM);

        YearMonth targetPeriod = (period != null) ? YearMonth.parse(period) : null;

        FeeStatementResponse response = feeStatementService.getTeamFeeStatement(teamId, targetPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
