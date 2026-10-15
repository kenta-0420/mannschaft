package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.advertising.service.OperationalAdCampaignService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
 * 運用型キャンペーン CRUD（チーム広告主向け。F09.19 §6.5 / §16 F09.19.5 AC-5.1）。
 *
 * <p>組織版 {@link OrganizationOperationalAdCampaignController} と対称で、scope を TEAM に固定する。
 * 認可は {@code AccessControlService.checkAdminOrAbove}（当該チーム ADMIN 以上）+ 当該チームの
 * 広告主アカウント（ACTIVE・未削除）の存在検証。他チームの ADMIN は越境として 403（COMMON_002）。
 * ad_campaigns は F09.19.5 で advertiser_account_id 直結に scope 化済みのため、チームでも構造的に
 * キャンペーンを保持できる。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser/ad-campaigns")
@RequiredArgsConstructor
public class TeamAdvertiserOperationalAdCampaignController {

    private final OperationalAdCampaignService operationalAdCampaignService;
    private final AccessControlService accessControlService;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    /**
     * ADMIN 以上かつ当該チームに ACTIVE な広告主アカウントが存在することを検証する。
     * いずれかを満たさなければ 403（存在有無を問わず）。
     */
    private void verifyAdvertiserAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
        AdvertiserAccountEntity account = advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, teamId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        if (account.getStatus() != AdvertiserAccountStatus.ACTIVE) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /** 作成（DRAFT）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OperationalCampaignResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateOperationalCampaignRequest request) {
        verifyAdvertiserAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.create(
                ScopeType.TEAM, teamId, userId, request));
    }

    /** 一覧（status フィルタ・ページング・created_at DESC）。 */
    @GetMapping
    public PagedResponse<OperationalCampaignResponse> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        verifyAdvertiserAccess(teamId);
        return operationalAdCampaignService.list(ScopeType.TEAM, teamId, status, page, size);
    }

    /** 詳細。 */
    @GetMapping("/{campaignId}")
    public ApiResponse<OperationalCampaignResponse> get(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyAdvertiserAccess(teamId);
        return ApiResponse.of(operationalAdCampaignService.get(
                ScopeType.TEAM, teamId, campaignId));
    }

    /** 編集（DRAFT / PAUSED のみ。POST と同形の全フィールド送信）。 */
    @PutMapping("/{campaignId}")
    public ApiResponse<OperationalCampaignResponse> update(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateOperationalCampaignRequest request) {
        verifyAdvertiserAccess(teamId);
        return ApiResponse.of(operationalAdCampaignService.update(
                ScopeType.TEAM, teamId, campaignId, request));
    }

    /** DRAFT → PENDING_REVIEW。 */
    @PostMapping("/{campaignId}/submit")
    public ApiResponse<OperationalCampaignResponse> submit(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyAdvertiserAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.submit(
                ScopeType.TEAM, teamId, campaignId, userId));
    }

    /** ACTIVE → PAUSED。 */
    @PostMapping("/{campaignId}/pause")
    public ApiResponse<OperationalCampaignResponse> pause(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyAdvertiserAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.pause(
                ScopeType.TEAM, teamId, campaignId, userId));
    }

    /** PAUSED → ACTIVE。 */
    @PostMapping("/{campaignId}/resume")
    public ApiResponse<OperationalCampaignResponse> resume(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyAdvertiserAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.resume(
                ScopeType.TEAM, teamId, campaignId, userId));
    }

    /** ACTIVE / PAUSED → ENDED（終端・不可逆）。 */
    @PostMapping("/{campaignId}/end")
    public ApiResponse<OperationalCampaignResponse> end(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyAdvertiserAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(operationalAdCampaignService.end(
                ScopeType.TEAM, teamId, campaignId, userId));
    }
}
