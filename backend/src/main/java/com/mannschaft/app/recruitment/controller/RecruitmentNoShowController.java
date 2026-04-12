package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.DisputeNoShowRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentNoShowRecordResponse;
import com.mannschaft.app.recruitment.dto.ResolveDisputeRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.service.RecruitmentNoShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11 Phase 5b: NO_SHOW マーク・異議申立 Controller (§9.5)。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "F03.11 募集型予約 / NO_SHOW管理", description = "NO_SHOWマーク・異議申立・解決")
@RequiredArgsConstructor
public class RecruitmentNoShowController {

    private final RecruitmentNoShowService noShowService;

    /**
     * 管理者が参加者を NO_SHOW としてマーク（仮マーク = confirmed=false）。
     * 24時間後に確定バッチが confirmed=true にする。
     */
    @PostMapping("/scopes/{scopeType}/{scopeId}/recruitment-listings/{listingId}/participants/{participantId}/no-show")
    @Operation(summary = "NO_SHOWマーク (管理者, §9.5)", description = "仮マーク。24h 後に確定バッチが confirmed=true にする。")
    public ResponseEntity<ApiResponse<RecruitmentNoShowRecordResponse>> markNoShow(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long listingId,
            @PathVariable Long participantId) {
        RecruitmentNoShowRecordEntity record = noShowService.markNoShow(
                participantId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(toResponse(record)));
    }

    /**
     * スコープの NO_SHOW 記録一覧（管理者用）。
     */
    @GetMapping("/scopes/{scopeType}/{scopeId}/no-shows")
    @Operation(summary = "スコープのNO_SHOW一覧 (管理者, §9.5)")
    public ResponseEntity<PagedResponse<RecruitmentNoShowRecordResponse>> listNoShows(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RecruitmentScopeType scope = RecruitmentScopeType.valueOf(scopeType.toUpperCase());
        List<RecruitmentNoShowRecordEntity> records =
                noShowService.getNoShowsByScope(scope, scopeId, SecurityUtils.getCurrentUserId());

        // ページング（メモリ内スライス）
        int total = records.size();
        int totalPages = (total + size - 1) / size;
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<RecruitmentNoShowRecordResponse> content = records.subList(fromIndex, toIndex)
                .stream().map(this::toResponse).toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(total, page, size, totalPages);
        return ResponseEntity.ok(PagedResponse.of(content, meta));
    }

    /**
     * 自分の NO_SHOW 履歴取得（ユーザー本人）。
     */
    @GetMapping("/recruitment/no-shows/me")
    @Operation(summary = "自分のNO_SHOW履歴 (本人, §9.5)")
    public ResponseEntity<ApiResponse<List<RecruitmentNoShowRecordResponse>>> getMyNoShows() {
        List<RecruitmentNoShowRecordEntity> records =
                noShowService.getMyHistory(SecurityUtils.getCurrentUserId());
        List<RecruitmentNoShowRecordResponse> responses = records.stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 異議申立（本人）。
     */
    @PostMapping("/recruitment/no-shows/{noShowId}/dispute")
    @Operation(summary = "NO_SHOW 異議申立 (本人, §9.5)")
    public ResponseEntity<ApiResponse<RecruitmentNoShowRecordResponse>> dispute(
            @PathVariable Long noShowId,
            @Valid @RequestBody DisputeNoShowRequest request) {
        RecruitmentNoShowRecordEntity record =
                noShowService.dispute(noShowId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(record)));
    }

    /**
     * 異議申立解決（管理者）。
     */
    @PatchMapping("/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute")
    @Operation(summary = "NO_SHOW 異議解決 (管理者, §9.5)")
    public ResponseEntity<ApiResponse<RecruitmentNoShowRecordResponse>> resolveDispute(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long noShowId,
            @Valid @RequestBody ResolveDisputeRequest request) {
        RecruitmentNoShowRecordEntity record = noShowService.resolveDispute(
                noShowId, SecurityUtils.getCurrentUserId(),
                RecruitmentScopeType.valueOf(scopeType), scopeId,
                request.getResolution());
        return ResponseEntity.ok(ApiResponse.of(toResponse(record)));
    }

    // ===========================================
    // プライベートマッパー
    // ===========================================

    private RecruitmentNoShowRecordResponse toResponse(RecruitmentNoShowRecordEntity entity) {
        return new RecruitmentNoShowRecordResponse(
                entity.getId(),
                entity.getParticipantId(),
                entity.getListingId(),
                entity.getUserId(),
                entity.getReason() != null ? entity.getReason().name() : null,
                entity.isConfirmed(),
                entity.getRecordedAt() != null ? entity.getRecordedAt().toString() : null,
                entity.getRecordedBy(),
                entity.isDisputed(),
                entity.getDisputeResolution() != null ? entity.getDisputeResolution().name() : null,
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null
        );
    }
}
