package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignTransitionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F09.17 Phase 11-b ε-A メッセージ型キャンペーン状態遷移 API。
 *
 * <p>所有者 (組織 ADMIN 以上) 向けの遷移エンドポイントを提供する。設計書 §4 に対応:</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/submit}  DRAFT → REVIEW</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/cancel}  DRAFT/REVIEW → CANCELLED</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/launch}  APPROVED → SCHEDULED or DELIVERING</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/pause}   DELIVERING → PAUSED</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/resume}  PAUSED → DELIVERING</li>
 * </ul>
 *
 * <p>SYSTEM_ADMIN の {@code approve} / {@code block} はすでに
 * {@link SystemAdminAdCampaignController} で提供済のためここでは扱わない。</p>
 *
 * <p>テナント越境制御は {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} と
 * Service 層の {@code organization_id} フィルタの 2 段構えで担保する。
 * 同 controller の DRAFT CRUD 側 ({@link AdvertiserMessagingCampaignController}) と同形。</p>
 */
@RestController
@RequestMapping("/api/v1/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class AdMessagingCampaignTransitionController {

    private final AdMessagingCampaignTransitionService transitionService;
    private final AccessControlService accessControlService;

    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
    }

    /**
     * DRAFT → REVIEW (自動 NG 検知の結果次第で BLOCKED 直行もあり)。
     */
    @PostMapping("/{id}/submit")
    public ApiResponse<CampaignDetailResponse> submit(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.submit(id, organizationId, userId));
    }

    /**
     * DRAFT/REVIEW → CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<CampaignDetailResponse> cancel(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.cancel(id, organizationId, userId));
    }

    /**
     * APPROVED → SCHEDULED または DELIVERING。
     */
    @PostMapping("/{id}/launch")
    public ApiResponse<CampaignDetailResponse> launch(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.launch(id, organizationId, userId));
    }

    /**
     * DELIVERING → PAUSED。
     */
    @PostMapping("/{id}/pause")
    public ApiResponse<CampaignDetailResponse> pause(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.pause(id, organizationId, userId));
    }

    /**
     * PAUSED → DELIVERING (credit_limit 再判定)。
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<CampaignDetailResponse> resume(
            @PathVariable UUID id,
            @RequestParam Long organizationId) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.resume(id, organizationId, userId));
    }
}
