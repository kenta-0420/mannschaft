package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.AudienceConfigRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.EstimatedReachRangeResponse;
import com.mannschaft.app.advertising.campaign.dto.UpdateCampaignRequest;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignService;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 Phase 11-d-2 組織スコープ メッセージ型キャンペーン API。
 *
 * <p>基底パス {@code /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging}。
 * 旧 {@link AdvertiserMessagingCampaignController} の scope ベース後継。</p>
 *
 * <p>テナント越境制御は {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} と
 * Service 層の scope_type/scope_id フィルタの 2 段構えで担保する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class OrganizationAdvertiserMessagingCampaignController {

    private final AdMessagingCampaignService campaignService;
    private final AdvertiserAccountService advertiserAccountService;
    private final AccessControlService accessControlService;

    /**
     * 組織スコープの権限検証。指定組織の ADMIN 以上であることを確認する。
     */
    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, ScopeType.ORGANIZATION.name());
    }

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    @GetMapping
    public PagedResponse<CampaignListItemResponse> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) AdCampaignStatus status,
            Pageable pageable) {
        verifyOrganizationAccess(organizationId);
        Page<CampaignListItemResponse> page =
                campaignService.listCampaigns(ScopeType.ORGANIZATION, organizationId, status, pageable);
        // status フィルタで除外された行は null になるため除去する。
        List<CampaignListItemResponse> filtered = page.getContent().stream()
                .filter(item -> item != null)
                .toList();
        return PagedResponse.of(
                filtered,
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> get(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(
                campaignService.getCampaign(id, ScopeType.ORGANIZATION, organizationId));
    }

    // ─────────────────────────────────────────────
    // 作成 / 編集 / 削除
    // ─────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignDetailResponse> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateCampaignRequest request) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        AdvertiserAccountResponse account =
                advertiserAccountService.getByScope(ScopeType.ORGANIZATION, organizationId);
        return ApiResponse.of(campaignService.createCampaign(
                ScopeType.ORGANIZATION, organizationId, account.id(), userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> update(
            @PathVariable Long organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCampaignRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.updateCampaign(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        campaignService.softDeleteCampaign(id, ScopeType.ORGANIZATION, organizationId);
    }

    // ─────────────────────────────────────────────
    // チャネル CRUD
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignChannelResponse> addChannel(
            @PathVariable Long organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.addChannel(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }

    @PutMapping("/{id}/channels/{channelId}")
    public ApiResponse<CampaignChannelResponse> updateChannel(
            @PathVariable Long organizationId,
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.updateChannel(
                channelId, ScopeType.ORGANIZATION, organizationId, request));
    }

    @DeleteMapping("/{id}/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeChannel(
            @PathVariable Long organizationId,
            @PathVariable UUID id,
            @PathVariable UUID channelId) {
        verifyOrganizationAccess(organizationId);
        campaignService.removeChannel(channelId, ScopeType.ORGANIZATION, organizationId);
    }

    // ─────────────────────────────────────────────
    // ターゲティング設定
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/audience")
    public ApiResponse<List<AudienceSegmentResponse>> setAudience(
            @PathVariable Long organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody AudienceConfigRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.setAudience(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }

    /**
     * F09.19.7 §10.2 / AC-7.2: 推定リーチのレンジを返す（ウィザード Step4 用）。
     */
    @PostMapping("/{id}/preview")
    public ApiResponse<EstimatedReachRangeResponse> preview(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.preview(id, ScopeType.ORGANIZATION, organizationId));
    }
}
