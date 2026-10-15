package com.mannschaft.app.advertising.ranking.controller;

import com.mannschaft.app.advertising.ranking.dto.CreateItemExclusionRequest;
import com.mannschaft.app.advertising.ranking.dto.EquipmentRankingExclusionResponse;
import com.mannschaft.app.advertising.ranking.dto.EquipmentRankingStatsResponse;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingExclusionEntity;
import com.mannschaft.app.advertising.ranking.entity.ExclusionType;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingBatchService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.RankingStatsResult;
import com.mannschaft.app.advertising.ranking.EquipmentRankingErrorCode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.repository.TeamRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 備品ランキング管理コントローラー（SYSTEM_ADMIN用）。
 * 統計情報取得・バッチ手動起動・除外設定管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/equipment-rankings")
@Tag(name = "備品ランキング管理（SYSTEM_ADMIN）", description = "F09.12 備品ランキング管理API")
@RequiredArgsConstructor
@Slf4j
public class EquipmentRankingAdminController {

    private static final String BATCH_LOCK_KEY = "mannschaft:lock:equipment-ranking-batch";

    private final EquipmentRankingService rankingService;
    private final EquipmentRankingBatchService batchService;
    private final TeamRepository teamRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final AccessControlService accessControlService;

    /**
     * 備品ランキング集計統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "備品ランキング統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EquipmentRankingStatsResponse>> getStats() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(currentUserId);
        RankingStatsResult result = rankingService.getStats();
        EquipmentRankingStatsResponse response = new EquipmentRankingStatsResponse(
                result.lastCalculatedAt(),
                result.totalVisibleItems(),
                result.availableTemplates(),
                result.optOutTeamCount(),
                result.excludedItemCount(),
                result.minTeamCountThreshold(),
                result.itemsBelowThreshold(),
                result.itemsWithAsin());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 備品ランキング再集計バッチを手動で起動する。
     * 排他制御あり（同時実行防止）。
     */
    @PostMapping("/recalculate")
    @Operation(summary = "備品ランキング再集計（手動起動）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "バックグラウンド処理開始")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculate() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(currentUserId);
        // 排他制御: 実行中チェック
        Boolean isLocked = stringRedisTemplate.hasKey(BATCH_LOCK_KEY);
        if (Boolean.TRUE.equals(isLocked)) {
            throw new BusinessException(EquipmentRankingErrorCode.BATCH_ALREADY_RUNNING);
        }

        // バックグラウンドで実行
        runBatchAsync();

        Map<String, Object> body = Map.of(
                "message", "再集計をバックグラウンドで開始しました",
                "estimated_duration_seconds", 120);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(body));
    }

    /**
     * 備品ランキング除外設定一覧を取得する。
     */
    @GetMapping("/exclusions")
    @Operation(summary = "備品ランキング除外設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EquipmentRankingExclusionResponse>>> getExclusions() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(currentUserId);
        List<EquipmentRankingExclusionEntity> exclusions = rankingService.getAllExclusions();
        List<EquipmentRankingExclusionResponse> responses = exclusions.stream()
                .map(this::toExclusionResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 備品を除外設定に追加する（ITEM_EXCLUSION）。
     */
    @PostMapping("/exclusions")
    @Operation(summary = "備品除外設定追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<EquipmentRankingExclusionResponse>> addExclusion(
            @Valid @RequestBody CreateItemExclusionRequest request) {
        Long adminUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(adminUserId);
        EquipmentRankingExclusionEntity entity = rankingService.addItemExclusion(
                request.getNormalizedName(), request.getReason(), adminUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(toExclusionResponse(entity)));
    }

    /**
     * 備品の除外設定を削除する。
     *
     * @param id 除外設定ID
     */
    @DeleteMapping("/exclusions/{id}")
    @Operation(summary = "備品除外設定削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeExclusion(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(currentUserId);
        rankingService.removeItemExclusion(id);
        return ResponseEntity.noContent().build();
    }

    // ---- ヘルパー ----

    @Async("job-pool")
    public void runBatchAsync() {
        log.info("備品ランキング再集計バッチを手動起動しました");
        batchService.runBatch();
    }

    private EquipmentRankingExclusionResponse toExclusionResponse(EquipmentRankingExclusionEntity entity) {
        String teamName = null;
        if (ExclusionType.TEAM_OPT_OUT.equals(entity.getExclusionType()) && entity.getTeamId() != null) {
            teamName = teamRepository.findById(entity.getTeamId())
                    .map(t -> t.getName())
                    .orElse(null);
        }
        return new EquipmentRankingExclusionResponse(
                entity.getId(),
                entity.getExclusionType().name(),
                entity.getTeamId(),
                teamName,
                entity.getNormalizedName(),
                entity.getReason(),
                entity.getExcludedByUserId(),
                entity.getCreatedAt());
    }
}
