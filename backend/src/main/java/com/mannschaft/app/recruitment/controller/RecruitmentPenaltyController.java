package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.LiftPenaltyRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentPenaltySettingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentUserPenaltyResponse;
import com.mannschaft.app.recruitment.dto.UpsertPenaltySettingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyService;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltySettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11 Phase 5b: ペナルティ設定・管理 Controller (§9.5)。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "F03.11 募集型予約 / ペナルティ管理", description = "ペナルティ設定・適用・解除")
@RequiredArgsConstructor
public class RecruitmentPenaltyController {

    private final RecruitmentPenaltySettingService settingService;
    private final RecruitmentPenaltyService penaltyService;

    /**
     * ペナルティ設定取得。
     */
    @GetMapping("/scopes/{scopeType}/{scopeId}/penalty-settings")
    @Operation(summary = "ペナルティ設定取得 (管理者)")
    public ResponseEntity<ApiResponse<RecruitmentPenaltySettingResponse>> getSetting(
            @PathVariable String scopeType,
            @PathVariable Long scopeId) {
        RecruitmentScopeType scope = RecruitmentScopeType.valueOf(scopeType.toUpperCase());
        RecruitmentPenaltySettingEntity entity =
                settingService.getSetting(scope, scopeId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toSettingResponse(entity)));
    }

    /**
     * ペナルティ設定更新（UPSERT）。
     */
    @PutMapping("/scopes/{scopeType}/{scopeId}/penalty-settings")
    @Operation(summary = "ペナルティ設定更新/作成 (管理者, UPSERT)")
    public ResponseEntity<ApiResponse<RecruitmentPenaltySettingResponse>> upsertSetting(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody UpsertPenaltySettingRequest request) {
        RecruitmentScopeType scope = RecruitmentScopeType.valueOf(scopeType.toUpperCase());
        RecruitmentPenaltySettingEntity entity = settingService.upsertSetting(
                scope, scopeId, SecurityUtils.getCurrentUserId(),
                request.isEnabled(),
                request.getThresholdCount(),
                request.getThresholdPeriodDays(),
                request.getPenaltyDurationDays(),
                request.getApplyScope(),
                request.isAutoNoShowDetection(),
                request.getDisputeAllowedDays());
        return ResponseEntity.ok(ApiResponse.of(toSettingResponse(entity)));
    }

    /**
     * スコープのペナルティ一覧（管理者用）。
     */
    @GetMapping("/scopes/{scopeType}/{scopeId}/penalties")
    @Operation(summary = "スコープのペナルティ一覧 (管理者)")
    public ResponseEntity<PagedResponse<RecruitmentUserPenaltyResponse>> listPenalties(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RecruitmentScopeType scope = RecruitmentScopeType.valueOf(scopeType.toUpperCase());
        List<RecruitmentUserPenaltyEntity> penalties =
                penaltyService.getActivePenalties(scope, scopeId, SecurityUtils.getCurrentUserId());

        // ページング（メモリ内スライス）
        int total = penalties.size();
        int totalPages = (total + size - 1) / size;
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<RecruitmentUserPenaltyResponse> content = penalties.subList(fromIndex, toIndex)
                .stream().map(this::toPenaltyResponse).toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(total, page, size, totalPages);
        return ResponseEntity.ok(PagedResponse.of(content, meta));
    }

    /**
     * ペナルティ手動解除（管理者）。
     */
    @PostMapping("/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift")
    @Operation(summary = "ペナルティ手動解除 (管理者)")
    public ResponseEntity<ApiResponse<RecruitmentUserPenaltyResponse>> liftPenalty(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long penaltyId,
            @Valid @RequestBody LiftPenaltyRequest request) {
        RecruitmentUserPenaltyEntity entity =
                penaltyService.liftPenalty(penaltyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toPenaltyResponse(entity)));
    }

    /**
     * 自分のペナルティ確認（本人）。
     */
    @GetMapping("/recruitment/penalties/me")
    @Operation(summary = "自分のペナルティ一覧 (本人)")
    public ResponseEntity<ApiResponse<List<RecruitmentUserPenaltyResponse>>> getMyPenalties() {
        List<RecruitmentUserPenaltyEntity> penalties =
                penaltyService.getMyPenalties(SecurityUtils.getCurrentUserId());
        List<RecruitmentUserPenaltyResponse> responses = penalties.stream()
                .map(this::toPenaltyResponse).toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    // ===========================================
    // プライベートマッパー
    // ===========================================

    private RecruitmentPenaltySettingResponse toSettingResponse(RecruitmentPenaltySettingEntity entity) {
        return new RecruitmentPenaltySettingResponse(
                entity.getId(),
                entity.getScopeType() != null ? entity.getScopeType().name() : null,
                entity.getScopeId(),
                entity.isEnabled(),
                entity.getThresholdCount(),
                entity.getThresholdPeriodDays(),
                entity.getPenaltyDurationDays(),
                entity.getApplyScope() != null ? entity.getApplyScope().name() : null,
                entity.isAutoNoShowDetection(),
                entity.getDisputeAllowedDays(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null
        );
    }

    private RecruitmentUserPenaltyResponse toPenaltyResponse(RecruitmentUserPenaltyEntity entity) {
        return new RecruitmentUserPenaltyResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getScopeType() != null ? entity.getScopeType().name() : null,
                entity.getScopeId(),
                entity.getPenaltyType(),
                entity.getStartedAt() != null ? entity.getStartedAt().toString() : null,
                entity.getExpiresAt() != null ? entity.getExpiresAt().toString() : null,
                entity.getLiftedAt() != null ? entity.getLiftedAt().toString() : null,
                entity.getLiftReason() != null ? entity.getLiftReason().name() : null,
                entity.isActive(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null
        );
    }
}
