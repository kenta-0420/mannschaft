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
 * F09.17 Phase 11-d-2 チームスコープ メッセージ型キャンペーン状態遷移 API。
 *
 * <p>基底パス {@code /api/v1/teams/{teamId}/advertiser/campaigns/messaging}。
 * チームが独立した広告主アカウントを持つ場合の submit/cancel/launch/pause/resume を提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class TeamAdMessagingCampaignTransitionController {

    private final AdMessagingCampaignTransitionService transitionService;
    private final AccessControlService accessControlService;

    private void verifyTeamAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
    }

    /**
     * DRAFT → REVIEW (自動 NG 検知の結果次第で BLOCKED 直行もあり)。
     */
    @PostMapping("/{id}/submit")
    public ApiResponse<CampaignDetailResponse> submit(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.submit(id, ScopeType.TEAM, teamId, userId));
    }

    /**
     * DRAFT/REVIEW → CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<CampaignDetailResponse> cancel(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.cancel(id, ScopeType.TEAM, teamId, userId));
    }

    /**
     * APPROVED → SCHEDULED または DELIVERING。
     */
    @PostMapping("/{id}/launch")
    public ApiResponse<CampaignDetailResponse> launch(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.launch(id, ScopeType.TEAM, teamId, userId));
    }

    /**
     * DELIVERING → PAUSED。
     */
    @PostMapping("/{id}/pause")
    public ApiResponse<CampaignDetailResponse> pause(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.pause(id, ScopeType.TEAM, teamId, userId));
    }

    /**
     * PAUSED → DELIVERING (credit_limit 再判定)。
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<CampaignDetailResponse> resume(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.resume(id, ScopeType.TEAM, teamId, userId));
    }
}
