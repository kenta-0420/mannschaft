package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.dto.RepairPlanTimelineResponse;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.RepairPlanTimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 地層タイムラインコントローラー（F08.8 Phase 3）。
 *
 * <p>URL: {@code GET /api/v1/{scope}/{scopeId}/repair-plan/timeline}</p>
 * <p>認可: スコープ所属確認（{@link AccessControlService#checkMembership}）。MEMBER 以上閲覧可。</p>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scope}/{scopeId}/repair-plan")
@Tag(name = "修繕計画タイムライン", description = "F08.8 Phase 3 地層タイムライン")
@RequiredArgsConstructor
public class RepairPlanTimelineController {

    private final RepairPlanTimelineService timelineService;
    private final AccessControlService accessControlService;

    /**
     * 地層タイムライン取得。
     *
     * @param scope    URL パスセグメント（{@code teams}/{@code organizations}）
     * @param scopeId  スコープ ID
     * @param yearFrom 集計開始年度（省略時: 現在年-20）
     * @param yearTo   集計終了年度（省略時: 現在年+10）
     */
    @GetMapping("/timeline")
    @Operation(
            summary = "地層タイムライン取得",
            description = "年度×部位×金額の積み上げデータ＋CPI・理事長層を返す。chart.js stacked bar 入力形式。"
    )
    public ResponseEntity<ApiResponse<RepairPlanTimelineResponse>> getTimeline(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo) {

        Long userId = SecurityUtils.getCurrentUserId();
        String scopeType = normalizeScopePathSegment(scope);
        accessControlService.checkMembership(userId, scopeId, scopeType);

        RepairPlanTimelineResponse response =
                timelineService.getTimeline(scopeType, scopeId, yearFrom, yearTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    private static String normalizeScopePathSegment(String pathSegment) {
        if (pathSegment == null) throw new BusinessException(CommonErrorCode.COMMON_001);
        return switch (pathSegment.toLowerCase()) {
            case "teams", "team" -> "TEAM";
            case "organizations", "organization" -> "ORGANIZATION";
            default -> throw new BusinessException(CommonErrorCode.COMMON_001);
        };
    }
}
