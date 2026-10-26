package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.ReportCreateRequest;
import com.mannschaft.app.village.dto.ReportResolveRequest;
import com.mannschaft.app.village.dto.ReportResponse;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.service.VillageReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 1 B7 — 村内通報 + モデレーション Controller。
 *
 * <p>担当 API（出陣指示書 §4.11 / 3 EP）:</p>
 * <ul>
 *   <li>{@code POST /api/v1/villages/{vid}/reports} — 通報送信（村人）</li>
 *   <li>{@code GET  /api/v1/villages/{vid}/reports?status=PENDING} — 一覧（HEADMAN / ELDER）</li>
 *   <li>{@code POST /api/v1/villages/{vid}/reports/{id}/resolve} — 解決（HEADMAN / ELDER）</li>
 * </ul>
 *
 * <p>通報者非開示の保証: レスポンスでは {@code reporter_user_id} を一切返さず、
 * {@code reporterDisplayName="ANONYMOUS_VILLAGER"} 固定（設計書 §6.2）。</p>
 *
 * <h2>認可</h2>
 * <p>全 EP の認可は {@link VillageReportService} 内で完結する。村の存在確認（削除・凍結を除外）の
 * のち、送信は村の現役メンバーであること（{@code isUserMember}）、一覧・解決は現役の
 * HEADMAN / ELDER であること（{@code requireModerator} が正準述語
 * {@code findActiveByVillageIdAndSubject} に委譲）を検証する。解決では通報エンティティの
 * {@code villageId} とパスの村 ID の一致も照合し、不一致は 404 で存在を秘匿する。</p>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/reports")
@Tag(name = "村内通報 + モデレーション (F17.1)",
     description = "Phase 1: 通報送信・一覧・解決（HEADMAN/ELDER のみ）")
@RequiredArgsConstructor
@AuthorizedInService
public class VillageReportController {

    private final VillageReportService reportService;

    @PostMapping
    @Operation(summary = "村内通報を送信する（村人のみ・10件/時のレートリミット）")
    public ResponseEntity<ApiResponse<ReportResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody ReportCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ReportResponse response = reportService.createReport(villageId, actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "村内通報の一覧を取得する（HEADMAN / ELDER のみ）")
    public ApiResponse<List<ReportResponse>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "status", required = false) VillageReportStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<ReportResponse> list = reportService.listReports(villageId, actorUserId, status, page, size);
        return ApiResponse.of(list);
    }

    @PostMapping("/{reportId}/resolve")
    @Operation(summary = "通報を解決する（HEADMAN / ELDER のみ）")
    public ApiResponse<ReportResponse> resolve(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody ReportResolveRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ReportResponse response = reportService.resolveReport(villageId, reportId, actorUserId, request);
        return ApiResponse.of(response);
    }
}
