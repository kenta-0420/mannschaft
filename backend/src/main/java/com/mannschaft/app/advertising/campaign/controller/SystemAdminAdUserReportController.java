package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.AdUserReportAdminResponse;
import com.mannschaft.app.advertising.campaign.dto.UpdateAdReportStatusRequest;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import com.mannschaft.app.advertising.campaign.service.AdReportService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F09.19.9 SYSTEM_ADMIN 通報一覧・状態遷移 API。
 *
 * <p>正本 §12。{@code GET /api/v1/system-admin/ad-user-reports}（status / reasonCode フィルタ + ページング）と
 * {@code PATCH /api/v1/system-admin/ad-user-reports/{id}/status}（NEW→REVIEWING→RESOLVED/DISMISSED）を提供する。
 * SYSTEM_ADMIN ガードは {@link AccessControlService#checkSystemAdmin}（DB ベース判定）で行い、
 * {@code /api/v1/system-admin/**} 一括ルールと二重に担保する（{@code SystemAdminOperationalAdCampaignController} 踏襲）。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/ad-user-reports")
@RequiredArgsConstructor
@Tag(name = "システム管理 - 広告通報", description = "F09.19.9 通報一覧・状態遷移（SYSTEM_ADMIN 専用）")
public class SystemAdminAdUserReportController {

    private final AdReportService adReportService;
    private final AccessControlService accessControlService;

    private Long requireSystemAdmin() {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(userId);
        return userId;
    }

    /** 通報一覧（status / reasonCode 任意フィルタ + ページング）。 */
    @GetMapping
    @Operation(summary = "通報一覧",
            description = "status / reasonCode で任意に絞り込んだ通報を created_at DESC で取得する。"
                    + "campaignId（メッセージ型）/ operationalCampaignId（運用型）を併記する。")
    public PagedResponse<AdUserReportAdminResponse> list(
            @RequestParam(required = false) AdReportStatus status,
            @RequestParam(required = false) AdReportReasonCode reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireSystemAdmin();
        return adReportService.listReports(status, reason, page, size);
    }

    /** 通報の状態遷移（NEW→REVIEWING→RESOLVED/DISMISSED）。不正遷移は 409 / AD_027。 */
    @PatchMapping("/{id}/status")
    @Operation(summary = "通報の状態遷移",
            description = "NEW→REVIEWING→RESOLVED/DISMISSED。不正遷移は 409 / AD_027、不存在は 404。")
    public ApiResponse<AdUserReportAdminResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAdReportStatusRequest request) {
        requireSystemAdmin();
        return ApiResponse.of(adReportService.updateStatus(id, request.status()));
    }
}
