package com.mannschaft.app.residencestatus.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.residencestatus.dto.MonitoringCommitteeVisitDto;
import com.mannschaft.app.residencestatus.service.MonitoringCommitteeVisitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.16 S3-C 見守り委員訪問記録コントローラー。
 *
 * <p>認可は Service 層に委譲する。Controller はパスパラメータの組織 ID と
 * 現在ユーザー ID の引き渡しのみを行う。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/residence-status/monitoring-visits")
@Tag(name = "見守り委員訪問記録（F09.16）", description = "F09.16 居住実態管理 - 見守り委員訪問記録 API")
@RequiredArgsConstructor
public class MonitoringCommitteeVisitController {

    private final MonitoringCommitteeVisitService visitService;

    /**
     * 訪問記録を作成する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @PostMapping
    @Operation(summary = "訪問記録作成（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<MonitoringCommitteeVisitDto>> createVisit(
            @PathVariable Long orgId,
            @Valid @RequestBody MonitoringCommitteeVisitCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        MonitoringCommitteeVisitDto dto = visitService.createVisit(
                orgId,
                request.committeeId(),
                request.residentRegistryId(),
                request.dwellingUnitId(),
                request.subjectUserId(),
                request.visitorUserId(),
                request.visitedAt(),
                request.contactResult(),
                request.considerationMemo(),
                request.nextVisitRecommendedAt(),
                request.consentCovenantId(),
                userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 委員会の訪問記録一覧を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @GetMapping
    @Operation(summary = "訪問記録一覧取得（committeeId または residentRegistryId で絞り込み）")
    public ResponseEntity<ApiResponse<List<MonitoringCommitteeVisitDto>>> getVisits(
            @PathVariable Long orgId,
            @RequestParam(required = false) Long committeeId,
            @RequestParam(required = false) Long residentRegistryId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<MonitoringCommitteeVisitDto> list;
        if (committeeId != null) {
            list = visitService.getVisitsByCommittee(orgId, committeeId, userId);
        } else {
            list = visitService.getVisitsByResident(orgId, residentRegistryId, userId);
        }
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 訪問記録を更新する（ADMIN/DEPUTY_ADMIN または訪問者本人）。
     */
    @PutMapping("/{id}")
    @Operation(summary = "訪問記録更新（ADMIN/DEPUTY_ADMIN または訪問者本人・24h 以内のみ）")
    public ResponseEntity<ApiResponse<MonitoringCommitteeVisitDto>> updateVisit(
            @PathVariable Long orgId,
            @PathVariable UUID id,
            @Valid @RequestBody MonitoringCommitteeVisitUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        MonitoringCommitteeVisitDto dto = visitService.updateVisit(
                orgId,
                id,
                userId,
                request.contactResult(),
                request.considerationMemo(),
                request.nextVisitRecommendedAt());
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    // ─────────────────────────────────────────────
    // リクエスト DTO（record）
    // ─────────────────────────────────────────────

    /**
     * 訪問記録作成リクエスト。
     */
    public record MonitoringCommitteeVisitCreateRequest(
            @NotNull Long committeeId,
            @NotNull Long residentRegistryId,
            @NotNull Long dwellingUnitId,
            @NotNull Long subjectUserId,
            @NotNull Long visitorUserId,
            @NotNull LocalDateTime visitedAt,
            @NotBlank String contactResult,
            String considerationMemo,
            LocalDate nextVisitRecommendedAt,
            UUID consentCovenantId
    ) {}

    /**
     * 訪問記録更新リクエスト。
     */
    public record MonitoringCommitteeVisitUpdateRequest(
            @NotBlank String contactResult,
            String considerationMemo,
            LocalDate nextVisitRecommendedAt
    ) {}
}
