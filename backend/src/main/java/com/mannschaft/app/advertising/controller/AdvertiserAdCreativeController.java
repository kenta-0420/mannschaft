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
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
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
 * 広告主向けクリエイティブ管理コントローラー。
 * <p>
 * 広告主が自分のキャンペーンに紐づくクリエイティブの CRUD を行う。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/advertiser/ad-campaigns/{campaignId}/creatives")
@RequiredArgsConstructor
public class AdvertiserAdCreativeController {

    private final AdCreativeService adCreativeService;
    private final AccessControlService accessControlService;
    private final AdCampaignRepository adCampaignRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    /**
     * ADMIN 以上であること、かつパス上の {@code campaignId} が当該組織の広告主に帰属することを検証する。
     *
     * <p>F09.19.1（既知 IDOR の閉塞）: 従来は {@code checkAdminOrAbove} のみで {@code campaignId} の帰属を
     * 未検証のまま Service へ渡していた。組織 A の ADMIN が自組織 URL に組織 B のキャンペーン ID を指定した
     * create/list/update/delete が素通しになるため、campaign→scope の帰属不一致は 403（存在有無を問わず）とする。</p>
     */
    private void verifyCampaignAccess(Long organizationId, Long campaignId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
        AdvertiserAccountEntity account = advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, organizationId)
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
            @PathVariable Long organizationId,
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateAdCreativeRequest request) {
        verifyCampaignAccess(organizationId, campaignId);
        return ApiResponse.of(adCreativeService.create(campaignId, request));
    }

    /**
     * キャンペーンに紐づくクリエイティブ一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<AdCreativeResponse>> list(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        verifyCampaignAccess(organizationId, campaignId);
        return ApiResponse.of(adCreativeService.findByCampaignId(campaignId));
    }

    /**
     * クリエイティブを更新する。
     */
    @PutMapping("/{adId}")
    public ApiResponse<AdCreativeResponse> update(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId,
            @PathVariable Long adId,
            @Valid @RequestBody UpdateAdCreativeRequest request) {
        verifyCampaignAccess(organizationId, campaignId);
        return ApiResponse.of(adCreativeService.update(adId, campaignId, request));
    }

    /**
     * クリエイティブを論理削除する（status = ENDED）。
     */
    @DeleteMapping("/{adId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId,
            @PathVariable Long adId) {
        verifyCampaignAccess(organizationId, campaignId);
        adCreativeService.delete(adId, campaignId);
    }
}
