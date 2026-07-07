package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.service.OperationalAdCampaignService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 運用型キャンペーン CRUD（組織広告主向け。F09.19 §6.5）。
 *
 * <p><b>試練段階の骨格</b>。認可（{@code AccessControlService.checkAdminOrAbove} + 広告主アカウント
 * 存在・ACTIVE 検証）を含む実装は出陣で行う。チーム対 URL（/teams/{teamId}/...）は F09.19.5。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/advertiser/ad-campaigns")
@RequiredArgsConstructor
public class OrganizationOperationalAdCampaignController {

    private final OperationalAdCampaignService operationalAdCampaignService;

    /** 作成（DRAFT）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OperationalCampaignResponse> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateOperationalCampaignRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.create(
                ScopeType.ORGANIZATION, organizationId, userId, request));
    }

    /** 一覧（status フィルタ・ページング・created_at DESC）。 */
    @GetMapping
    public PagedResponse<OperationalCampaignResponse> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityUtils.getCurrentUserId();
        return operationalAdCampaignService.list(ScopeType.ORGANIZATION, organizationId, status, page, size);
    }

    /** 詳細。 */
    @GetMapping("/{campaignId}")
    public ApiResponse<OperationalCampaignResponse> get(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.get(
                ScopeType.ORGANIZATION, organizationId, campaignId));
    }

    /** 編集（DRAFT / PAUSED のみ。POST と同形の全フィールド送信）。 */
    @PutMapping("/{campaignId}")
    public ApiResponse<OperationalCampaignResponse> update(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateOperationalCampaignRequest request) {
        SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.update(
                ScopeType.ORGANIZATION, organizationId, campaignId, request));
    }

    /** DRAFT → PENDING_REVIEW。 */
    @PostMapping("/{campaignId}/submit")
    public ApiResponse<OperationalCampaignResponse> submit(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.submit(
                ScopeType.ORGANIZATION, organizationId, campaignId, userId));
    }

    /** ACTIVE → PAUSED。 */
    @PostMapping("/{campaignId}/pause")
    public ApiResponse<OperationalCampaignResponse> pause(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.pause(
                ScopeType.ORGANIZATION, organizationId, campaignId, userId));
    }

    /** PAUSED → ACTIVE。 */
    @PostMapping("/{campaignId}/resume")
    public ApiResponse<OperationalCampaignResponse> resume(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.resume(
                ScopeType.ORGANIZATION, organizationId, campaignId, userId));
    }

    /** ACTIVE / PAUSED → ENDED（終端・不可逆）。 */
    @PostMapping("/{campaignId}/end")
    public ApiResponse<OperationalCampaignResponse> end(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.end(
                ScopeType.ORGANIZATION, organizationId, campaignId, userId));
    }
}
