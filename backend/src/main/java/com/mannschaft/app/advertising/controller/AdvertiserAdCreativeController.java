package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.advertising.service.AdCreativeService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
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

    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
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
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(adCreativeService.create(campaignId, request));
    }

    /**
     * キャンペーンに紐づくクリエイティブ一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<AdCreativeResponse>> list(
            @PathVariable Long organizationId,
            @PathVariable Long campaignId) {
        verifyOrganizationAccess(organizationId);
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
        verifyOrganizationAccess(organizationId);
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
        verifyOrganizationAccess(organizationId);
        adCreativeService.delete(adId, campaignId);
    }
}
