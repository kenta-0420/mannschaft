package com.mannschaft.app.advertising.ranking.controller;

import com.mannschaft.app.advertising.ranking.dto.EquipmentTrendingItemResponse;
import com.mannschaft.app.advertising.ranking.dto.EquipmentTrendingResponse;
import com.mannschaft.app.advertising.ranking.dto.OptOutStatusResponse;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.EquipmentTrendingResult;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 同類チーム備品ランキングコントローラー。
 * チームメンバー向けのランキング取得・opt-out操作APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/equipment/trending")
@Tag(name = "備品ランキング", description = "F09.12 同類チーム備品ランキング")
@RequiredArgsConstructor
public class EquipmentRankingController {

    private final EquipmentRankingService rankingService;
    private final AccessControlService accessControlService;

    /**
     * 同類チームの備品ランキングを取得する。
     *
     * @param teamId     チームID
     * @param category   カテゴリフィルタ（null = 全カテゴリ横断）
     * @param limit      返却件数（デフォルト10、最大20）
     * @param linkedOnly true の場合 ASIN あり（Amazonリンクあり）のみ返却
     */
    @GetMapping
    @Operation(summary = "同類チーム備品ランキング取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EquipmentTrendingResponse>> getTrending(
            @PathVariable Long teamId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "false") boolean linkedOnly) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, teamId, "TEAM");

        // limitの上限チェック
        int effectiveLimit = Math.min(limit, 20);

        EquipmentTrendingResult result = rankingService.getTrending(teamId, category, effectiveLimit, linkedOnly);
        EquipmentTrendingResponse response = toResponse(result);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームをランキングデータ提供からopt-outする。
     *
     * @param teamId チームID
     */
    @PostMapping("/opt-out")
    @Operation(summary = "備品ランキングopt-out")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "opt-out成功")
    public ResponseEntity<ApiResponse<OptOutStatusResponse>> optOut(@PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // ADMIN のみ許可
        if (!accessControlService.isAdmin(currentUserId, teamId, "TEAM")) {
            throw new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }

        rankingService.optOut(teamId, currentUserId);
        OptOutStatusResponse response = new OptOutStatusResponse(
                teamId,
                true,
                "次回の集計（翌日）以降、このチームのデータはランキングに含まれなくなります");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームのopt-outを解除する（データ提供を再開）。
     *
     * @param teamId チームID
     */
    @DeleteMapping("/opt-out")
    @Operation(summary = "備品ランキングopt-out解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "opt-out解除成功")
    public ResponseEntity<ApiResponse<OptOutStatusResponse>> optIn(@PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // ADMIN のみ許可
        if (!accessControlService.isAdmin(currentUserId, teamId, "TEAM")) {
            throw new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }

        rankingService.optIn(teamId);
        OptOutStatusResponse response = new OptOutStatusResponse(
                teamId,
                false,
                "次回の集計（翌日）以降、このチームのデータがランキングに再び含まれます");
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ---- 変換ヘルパー ----

    private EquipmentTrendingResponse toResponse(EquipmentTrendingResult result) {
        List<EquipmentTrendingItemResponse> items = result.ranking().stream()
                .map(entity -> toItemResponse(entity, result.amazonTag()))
                .toList();
        return new EquipmentTrendingResponse(
                result.teamTemplate(),
                result.category(),
                result.optOut(),
                items,
                result.totalTemplatesTeams(),
                result.calculatedAt());
    }

    private EquipmentTrendingItemResponse toItemResponse(EquipmentRankingEntity entity, String amazonTag) {
        String replenishUrl = null;
        if (entity.getAmazonAsin() != null && amazonTag != null) {
            replenishUrl = "https://www.amazon.co.jp/dp/" + entity.getAmazonAsin() + "?tag=" + amazonTag;
        }
        return new EquipmentTrendingItemResponse(
                entity.getRank(),
                entity.getItemName(),
                entity.getCategory(),
                entity.getTeamCount(),
                entity.getConsumeEventCount(),
                entity.getAmazonAsin(),
                replenishUrl);
    }
}
