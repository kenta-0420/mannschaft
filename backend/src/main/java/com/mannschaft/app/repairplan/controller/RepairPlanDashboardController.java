package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.RequireFeature;
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
 * URL パスの {scopeType} は teams または organizations の複数形。
 * 内部で TEAM / ORGANIZATION の大文字単数形に正規化してサービスに渡す。
 * @RequireRepairPlanModule が scopeTypeParam="scopeType" を参照するため
 * パス変数名を scopeType に統一している（他コントローラーと揃える）。
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan")
@Tag(name = "修繕計画ダッシュボード", description = "F08.8 マンション修繕長期計画ダッシュボード")
@RequiredArgsConstructor
public class RepairPlanDashboardController {

    private final RepairPlanDashboardService dashboardService;
    private final AccessControlService accessControlService;

    @GetMapping("/dashboard")
    @Operation(
            summary = "修繕計画ダッシュボード一括取得",
            description = "5 ペイン統合 DTO を返す。Phase 1 では次 5 年の修繕予定のみデータあり、"
                    + "残りペインは Phase 2 以降で実装。"
    )
    @RequireFeature("FEATURE_PROPERTY_REPAIRPLAN_ENABLED")
    public ResponseEntity<ApiResponse<RepairPlanDashboardResponse>> getDashboard(
            @PathVariable("scopeType") String scopeType,
            @PathVariable("scopeId") Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String normalizedScope = normalizeScopePathSegment(scopeType);
        accessControlService.checkMembership(userId, scopeId, normalizedScope);
        RepairPlanDashboardResponse response = dashboardService.get(scopeId, normalizedScope, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * URL パスセグメント（複数形）を TEAM / ORGANIZATION の大文字単数形に正規化する。
     * RepairPlanModuleGuard でも同様の正規化を行うが、サービス呼び出し前に正規化を完結させておく。
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
