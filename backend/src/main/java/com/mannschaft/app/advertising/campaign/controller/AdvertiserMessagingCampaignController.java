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
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.servlet.http.HttpServletResponse;
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
 * F09.17 Phase 11-a 広告主向けメッセージ型キャンペーン API (旧 organizationId クエリ式)。
 *
 * <p>基底パス {@code /api/v1/advertiser/campaigns/messaging}。</p>
 *
 * <p><strong>非推奨</strong> F09.17 Phase 11-d-2 で scope ベース URL
 * ({@link OrganizationAdvertiserMessagingCampaignController} /
 * {@link TeamAdvertiserMessagingCampaignController}) を導入。
 * 本 Controller は互換のため Phase 11-e まで残置するが、
 * 全エンドポイントに {@code Deprecation: true} と {@code Sunset} ヘッダを付与して
 * 段階的廃止を予告する。新規実装では新 URL を使用すること。</p>
 *
 * <p>内部実装は {@code scope_type=ORGANIZATION} 固定で scope ベースの Service を呼ぶ。</p>
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class AdvertiserMessagingCampaignController {

    /** 廃止予定日 (F09.17 Phase 11-e リリース予定日)。HTTP-date 形式 (RFC 7231)。 */
    private static final String SUNSET_DATE = "Wed, 31 Dec 2026 23:59:59 GMT";

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

    /**
     * 全エンドポイント共通の deprecation 警告ヘッダを書き込む。
     */
    private void writeDeprecationHeaders(HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", SUNSET_DATE);
        response.setHeader(
                "Link",
                "</api/v1/organizations/{organizationId}/advertiser/campaigns/messaging>; "
                        + "rel=\"successor-version\"");
    }

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    @GetMapping
    public PagedResponse<CampaignListItemResponse> list(
            @RequestParam Long organizationId,
            @RequestParam(required = false) AdCampaignStatus status,
            Pageable pageable,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Page<CampaignListItemResponse> page =
                campaignService.listCampaigns(ScopeType.ORGANIZATION, organizationId, status, pageable);
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
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
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
            @RequestParam Long organizationId,
            @Valid @RequestBody CreateCampaignRequest request,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        AdvertiserAccountResponse account =
                advertiserAccountService.getByScope(ScopeType.ORGANIZATION, organizationId);
        return ApiResponse.of(campaignService.createCampaign(
                ScopeType.ORGANIZATION, organizationId, account.id(), userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CampaignDetailResponse> update(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody UpdateCampaignRequest request,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.updateCampaign(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        campaignService.softDeleteCampaign(id, ScopeType.ORGANIZATION, organizationId);
    }

    // ─────────────────────────────────────────────
    // チャネル CRUD
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignChannelResponse> addChannel(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody CampaignChannelRequest request,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.addChannel(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }

    @PutMapping("/{id}/channels/{channelId}")
    public ApiResponse<CampaignChannelResponse> updateChannel(
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @RequestParam Long organizationId,
            @Valid @RequestBody CampaignChannelRequest request,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        // {id} はパス上の整合性確認用。Service は channelId と scope で
        // キャンペーン到達性を検証するため、id は明示的に利用しない。
        return ApiResponse.of(campaignService.updateChannel(
                channelId, ScopeType.ORGANIZATION, organizationId, request));
    }

    @DeleteMapping("/{id}/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeChannel(
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        campaignService.removeChannel(channelId, ScopeType.ORGANIZATION, organizationId);
    }

    // ─────────────────────────────────────────────
    // ターゲティング設定
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/audience")
    public ApiResponse<List<AudienceSegmentResponse>> setAudience(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            @Valid @RequestBody AudienceConfigRequest request,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(campaignService.setAudience(
                id, ScopeType.ORGANIZATION, organizationId, request));
    }
}
