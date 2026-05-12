package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.dto.RepairPlanDashboardResponse;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.RepairPlanDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 修繕計画ダッシュボードコントローラー（F08.8 Phase 1）。
 *
 * <p>設計書 F08.8 §4 の {@code GET /api/v1/{scope}/{id}/repair-plan/dashboard} を提供する。
 * URL パスの {@code {scope}} は {@code teams} または {@code organizations} の複数形。
 * 内部で {@code TEAM} / {@code ORGANIZATION} の大文字単数形に正規化して
 * {@link RepairPlanDashboardService} に渡す。</p>
 *
 * <p>認可: スコープ所属確認のみ実施（{@link AccessControlService#checkMembership}）。
 * 個別ウィジェットの可視性はサービスで {@code widget_visibility[]} に反映される。
 * 監査ログは読み取り API のため記録しない（既存 read API 流儀）。</p>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scope}/{scopeId}/repair-plan")
@Tag(name = "修繕計画ダッシュボード", description = "F08.8 マンション修繕長期計画ダッシュボード")
@RequiredArgsConstructor
public class RepairPlanDashboardController {

    private final RepairPlanDashboardService dashboardService;
    private final AccessControlService accessControlService;

    /**
     * 修繕計画ダッシュボード一括取得。
     *
     * @param scope   URL パスセグメント。{@code teams} または {@code organizations}
     * @param scopeId スコープ ID（チーム ID または組織 ID）
     * @return 5 ペイン統合 DTO（Phase 1 では {@code upcoming_items} のみデータあり）
     */
    @GetMapping("/dashboard")
    @Operation(
            summary = "修繕計画ダッシュボード一括取得",
            description = "5 ペイン統合 DTO を返す。Phase 1 では次 5 年の修繕予定のみデータあり、"
                    + "残りペインは Phase 2 以降で実装。"
    )
    public ResponseEntity<ApiResponse<RepairPlanDashboardResponse>> getDashboard(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String scopeType = normalizeScopePathSegment(scope);

        // 認可: スコープ所属確認（非メンバーは 403）
        accessControlService.checkMembership(userId, scopeId, scopeType);

        RepairPlanDashboardResponse response = dashboardService.get(scopeId, scopeType, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * URL パスセグメント（複数形）を {@code TEAM} / {@code ORGANIZATION} の大文字単数形に正規化する。
     * F08.8 は teams / organizations のみ対応し、personal は対象外。
     */
    private static String normalizeScopePathSegment(String pathSegment) {
        if (pathSegment == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return switch (pathSegment.toLowerCase()) {
            case "teams", "team" -> "TEAM";
            case "organizations", "organization" -> "ORGANIZATION";
            default -> throw new BusinessException(CommonErrorCode.COMMON_001);
        };
    }
}
