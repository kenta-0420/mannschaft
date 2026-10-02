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
 * F09.17 Phase 11-d-2 チームスコープ メッセージ型キャンペーン API。
 *
 * <p>基底パス {@code /api/v1/teams/{teamId}/advertiser/campaigns/messaging}。
 * チームが独立した広告主アカウントを持つ場合の DRAFT CRUD・チャネル設定・ターゲティング設定を提供する。</p>
 *
 * <p>テナント越境制御は {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} で
 * 指定チームの ADMIN 以上を要求し、Service 層の scope_type/scope_id フィルタで 2 段防御する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class TeamAdvertiserMessagingCampaignController {

    private final AdMessagingCampaignService campaignService;
    private final AdvertiserAccountService advertiserAccountService;
    private final AccessControlService accessControlService;

    /**
     * チームスコープの権限検証。指定チームの ADMIN 以上であることを確認する。
     */
    private void verifyTeamAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
    }

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    @GetMapping
    public PagedResponse<CampaignListItemResponse> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) AdCampaignStatus status,
            Pageable pageable) {
        verifyTeamAccess(teamId);
        Page<CampaignListItemResponse> page =
                campaignService.listCampaigns(ScopeType.TEAM, teamId, status, pageable);
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
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.getCampaign(id, ScopeType.TEAM, teamId));
    }

    // ─────────────────────────────────────────────
    // 作成 / 編集 / 削除
    // ─────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignDetailResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCampaignRequest request) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        AdvertiserAccountResponse account =
                advertiserAccountService.getByScope(ScopeType.TEAM, teamId);
        return ApiResponse.of(campaignService.createCampaign(
                ScopeType.TEAM, teamId, account.id(), userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> update(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCampaignRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.updateCampaign(
                id, ScopeType.TEAM, teamId, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        campaignService.softDeleteCampaign(id, ScopeType.TEAM, teamId);
    }

    // ─────────────────────────────────────────────
    // チャネル CRUD
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignChannelResponse> addChannel(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.addChannel(
                id, ScopeType.TEAM, teamId, request));
    }

    @PutMapping("/{id}/channels/{channelId}")
    public ApiResponse<CampaignChannelResponse> updateChannel(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.updateChannel(
                channelId, ScopeType.TEAM, teamId, request));
    }

    @DeleteMapping("/{id}/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeChannel(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @PathVariable UUID channelId) {
        verifyTeamAccess(teamId);
        campaignService.removeChannel(channelId, ScopeType.TEAM, teamId);
    }

    // ─────────────────────────────────────────────
    // ターゲティング設定
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/audience")
    public ApiResponse<List<AudienceSegmentResponse>> setAudience(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @Valid @RequestBody AudienceConfigRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.setAudience(
                id, ScopeType.TEAM, teamId, request));
    }

    /**
     * F09.19.7 §10.2 / AC-7.2: 推定リーチのレンジを返す（ウィザード Step4 用）。
     */
    @PostMapping("/{id}/preview")
    public ApiResponse<EstimatedReachRangeResponse> preview(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignService.preview(id, ScopeType.TEAM, teamId));
    }
}
