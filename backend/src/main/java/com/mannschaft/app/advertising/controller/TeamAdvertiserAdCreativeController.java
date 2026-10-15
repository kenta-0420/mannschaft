package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.advertising.service.AdCreativeService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * チームスコープ 広告主向けクリエイティブ管理コントローラー（F09.19 §9 / §16 F09.19.5b）。
 *
 * <p>組織版 {@link AdvertiserAdCreativeController} を scope=TEAM で対称提供する。
 * チーム広告主が自チームのキャンペーンに紐づくクリエイティブの CRUD を行う。</p>
 *
 * <p><b>IDOR 対策（§11 / {@code project_matching_authz_userid_as_teamid_idor} の教訓）</b>:
 * {@code checkAdminOrAbove(userId, teamId, TEAM)} で当該チームの ADMIN 以上を確認したうえで、
 * パス上の {@code campaignId} が「当該チームの広告主アカウントに帰属するか」を必ず検証する。
 * scope 解決を ORGANIZATION のまま流用すると越境を素通しするため、TEAM/teamId で account を解決し
 * campaign→account の帰属不一致は 403（存在有無を問わず COMMON_002）とする。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser/ad-campaigns/{campaignId}/creatives")
@RequiredArgsConstructor
public class TeamAdvertiserAdCreativeController {

    private final AdCreativeService adCreativeService;
    private final AccessControlService accessControlService;
    private final AdCampaignRepository adCampaignRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    /**
     * ADMIN 以上であること、かつパス上の {@code campaignId} が当該チームの広告主に帰属することを検証する。
     */
    private void verifyCampaignAccess(Long teamId, Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
        AdvertiserAccountEntity account = advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, teamId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        AdCampaignEntity campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        if (!Objects.equals(campaign.getAdvertiserAccountId(), account.getId())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * キャンペーンに紐づくクリエイティブを新規作成する。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdCreativeResponse> create(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateAdCreativeRequest request) {
        verifyCampaignAccess(teamId, campaignId);
        return ApiResponse.of(adCreativeService.create(campaignId, request));
    }

    /**
     * キャンペーンに紐づくクリエイティブ一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<AdCreativeResponse>> list(
            @PathVariable Long teamId,
            @PathVariable Long campaignId) {
        verifyCampaignAccess(teamId, campaignId);
        return ApiResponse.of(adCreativeService.findByCampaignId(campaignId));
    }

    /**
     * クリエイティブを更新する。
     */
    @PutMapping("/{adId}")
    public ApiResponse<AdCreativeResponse> update(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @PathVariable Long adId,
            @Valid @RequestBody UpdateAdCreativeRequest request) {
        verifyCampaignAccess(teamId, campaignId);
        return ApiResponse.of(adCreativeService.update(adId, campaignId, request));
    }

    /**
     * クリエイティブを論理削除する（status = ENDED）。
     */
    @DeleteMapping("/{adId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @PathVariable Long adId) {
        verifyCampaignAccess(teamId, campaignId);
        adCreativeService.delete(adId, campaignId);
    }
}
