package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.AudienceConfigRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.UpdateCampaignRequest;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignService;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 * F09.17 Phase 11-a 広告主向けメッセージ型キャンペーン API。
 *
 * <p>基底パス {@code /api/v1/advertiser/campaigns/messaging}。
 * DRAFT 作成・編集・チャネル CRUD・ターゲティング設定までを提供する。
 * submit / launch / pause / preview / report 等の状態遷移系・分析系は
 * Phase 11-b で実装する。</p>
 *
 * <p>テナント越境制御は {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} と
 * Service 層の {@code organization_id} フィルタの 2 段構えで担保する。</p>
 */
@RestController
@RequestMapping("/api/v1/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class AdvertiserMessagingCampaignController {

    private final AdMessagingCampaignService campaignService;
    private final AdvertiserAccountService advertiserAccountService;
    private final AccessControlService accessControlService;

    /**
     * 組織スコープの権限検証。指定組織の ADMIN 以上であることを確認する。
     */
    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
    }

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    /**
     * 自組織が所有するメッセージ型キャンペーンを一覧する。
     */
    @GetMapping
    public PagedResponse<CampaignListItemResponse> list(
            @RequestParam Long organizationId,
            @RequestParam(required = false) AdCampaignStatus status,
            Pageable pageable) {
        verifyOrganizationAccess(organizationId);
        Page<CampaignListItemResponse> page =
                campaignService.listCampaigns(organizationId, status, pageable);
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

    /**
     * キャンペーン詳細を取得する（チャネル一覧 + ターゲティング条件込み）。
     */
    @GetMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> get(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.getCampaign(id, organizationId));
    }

    // ─────────────────────────────────────────────
    // 作成 / 編集 / 削除
    // ─────────────────────────────────────────────

    /**
     * 新規キャンペーンを DRAFT で作成する。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignDetailResponse> create(
            @RequestParam Long organizationId,
            @Valid @RequestBody CreateCampaignRequest request) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        AdvertiserAccountResponse account =
                advertiserAccountService.getByOrganizationId(organizationId);
        return ApiResponse.of(
                campaignService.createCampaign(organizationId, account.id(), userId, request));
    }

    /**
     * DRAFT 状態のキャンペーンを編集する。
     */
    @PutMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> update(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody UpdateCampaignRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.updateCampaign(id, organizationId, request));
    }

    /**
     * DRAFT 状態のキャンペーンを論理削除する。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        campaignService.softDeleteCampaign(id, organizationId);
    }

    // ─────────────────────────────────────────────
    // チャネル CRUD
    // ─────────────────────────────────────────────

    /**
     * キャンペーンにチャネル別コンテンツを追加する。
     */
    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignChannelResponse> addChannel(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.addChannel(id, organizationId, request));
    }

    /**
     * チャネル別コンテンツを更新する。
     */
    @PutMapping("/{id}/channels/{channelId}")
    public ApiResponse<CampaignChannelResponse> updateChannel(
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @RequestParam Long organizationId,
            @Valid @RequestBody CampaignChannelRequest request) {
        verifyOrganizationAccess(organizationId);
        // {id} はパス上の整合性確認用。Service は channelId と organizationId で
        // キャンペーン到達性を検証するため、id は明示的に利用しない。
        return ApiResponse.of(campaignService.updateChannel(channelId, organizationId, request));
    }

    /**
     * チャネル別コンテンツを削除する。
     */
    @DeleteMapping("/{id}/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeChannel(
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        campaignService.removeChannel(channelId, organizationId);
    }

    // ─────────────────────────────────────────────
    // ターゲティング設定
    // ─────────────────────────────────────────────

    /**
     * キャンペーンのターゲティング条件を全件 replace する。
     */
    @PostMapping("/{id}/audience")
    public ApiResponse<List<AudienceSegmentResponse>> setAudience(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody AudienceConfigRequest request) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.setAudience(id, organizationId, request));
    }
}
