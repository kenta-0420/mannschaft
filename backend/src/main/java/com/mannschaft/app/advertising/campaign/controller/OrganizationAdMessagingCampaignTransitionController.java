package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignTransitionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F09.17 Phase 11-d-2 組織スコープ メッセージ型キャンペーン状態遷移 API。
 *
 * <p>基底パス {@code /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging}。
 * 旧 {@link AdMessagingCampaignTransitionController} の scope ベース後継。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class OrganizationAdMessagingCampaignTransitionController {

    private final AdMessagingCampaignTransitionService transitionService;
    private final AccessControlService accessControlService;

    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, ScopeType.ORGANIZATION.name());
    }

    /**
     * DRAFT → REVIEW (自動 NG 検知の結果次第で BLOCKED 直行もあり)。
     */
    @PostMapping("/{id}/submit")
    public ApiResponse<CampaignDetailResponse> submit(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.submit(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    /**
     * DRAFT/REVIEW → CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<CampaignDetailResponse> cancel(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.cancel(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    /**
     * APPROVED → SCHEDULED または DELIVERING。
     */
    @PostMapping("/{id}/launch")
    public ApiResponse<CampaignDetailResponse> launch(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.launch(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    /**
     * DELIVERING → PAUSED。
     */
    @PostMapping("/{id}/pause")
    public ApiResponse<CampaignDetailResponse> pause(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.pause(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    /**
     * PAUSED → DELIVERING (credit_limit 再判定)。
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<CampaignDetailResponse> resume(
            @PathVariable Long organizationId,
            @PathVariable UUID id) {
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.resume(id, ScopeType.ORGANIZATION, organizationId, userId));
    }
}
