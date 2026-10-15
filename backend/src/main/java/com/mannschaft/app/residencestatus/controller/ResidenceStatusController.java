package com.mannschaft.app.residencestatus.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.residencestatus.dto.ActivitySnapshotDto;
import com.mannschaft.app.residencestatus.dto.ResidenceStatusDashboardDto;
import com.mannschaft.app.residencestatus.service.ResidentActivityAggregatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 居住実態管理コントローラー（F09.16 S3-B）。
 *
 * <p>アクティビティスナップショット取得・ダッシュボード集計 API を提供する。
 * 認可は Service 層（{@link ResidentActivityAggregatorService}）に委譲する。
 * Controller はパスパラメータの組織 ID と現在ユーザー ID の引き渡しのみを行う。
 *
 * <p>設計書: {@code docs/features/F09.16_residence_status_monitoring.md} §6
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/residence-status")
@Tag(name = "居住実態管理（F09.16）", description = "F09.16 居住実態管理・見守り - アクティビティ集計・ダッシュボード API")
@RequiredArgsConstructor
public class ResidenceStatusController {

    private final ResidentActivityAggregatorService aggregatorService;

    /**
     * 推定スコア（アクティビティスナップショット）取得（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * <p>本人アクセスは禁止。本人アクセス時は 403 を返す。
     *
     * @param orgId              組織 ID
     * @param residentRegistryId 居住者台帳 ID
     * @return 直近 30 件のアクティビティスナップショット（新しい順）
     */
    @GetMapping("/activity-snapshots/{residentRegistryId}")
    @Operation(summary = "推定スコア取得（ADMIN/DEPUTY_ADMIN のみ・本人禁止）")
    public ResponseEntity<ApiResponse<List<ActivitySnapshotDto>>> getSnapshots(
            @PathVariable Long orgId,
            @PathVariable Long residentRegistryId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ActivitySnapshotDto> dtos = aggregatorService.getSnapshots(orgId, residentRegistryId, userId);
        return ResponseEntity.ok(ApiResponse.of(dtos));
    }

    /**
     * 統計ダッシュボード取得（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * <p>組織単位のリスクスコア分布・未反応者数・進行中年次キャンペーン数を返す。
     * TODO: Redis キャッシュ 5 分 TTL は別フェーズで {@code @Cacheable} 適用予定。
     *
     * @param orgId 組織 ID
     * @return ダッシュボード集計 DTO
     */
    @GetMapping("/dashboard")
    @Operation(summary = "居住実態統計ダッシュボード（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<ResidenceStatusDashboardDto>> getDashboard(
            @PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResidenceStatusDashboardDto dto = aggregatorService.getDashboard(orgId, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }
}
