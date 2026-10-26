package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignTransitionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F09.17 Phase 11-b ε-A メッセージ型キャンペーン状態遷移 API (旧 organizationId クエリ式)。
 *
 * <p>所有者 (組織 ADMIN 以上) 向けの遷移エンドポイントを提供する:</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/submit}  DRAFT → REVIEW</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/cancel}  DRAFT/REVIEW → CANCELLED</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/launch}  APPROVED → SCHEDULED or DELIVERING</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/pause}   DELIVERING → PAUSED</li>
 *   <li>{@code POST /api/v1/advertiser/campaigns/messaging/{id}/resume}  PAUSED → DELIVERING</li>
 * </ul>
 *
 * <p><strong>非推奨</strong> F09.17 Phase 11-d-2 で scope ベース URL
 * ({@link OrganizationAdMessagingCampaignTransitionController} /
 * {@link TeamAdMessagingCampaignTransitionController}) を導入。
 * 本 Controller は互換のため Phase 11-e まで残置するが、全エンドポイントに
 * {@code Deprecation: true} と {@code Sunset} ヘッダを付与して段階的廃止を予告する。</p>
 *
 * <p>SYSTEM_ADMIN の {@code approve} / {@code block} はすでに
 * {@link SystemAdminAdCampaignController} で提供済のためここでは扱わない。</p>
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/advertiser/campaigns/messaging")
@RequiredArgsConstructor
public class AdMessagingCampaignTransitionController {

    /** 廃止予定日 (F09.17 Phase 11-e リリース予定日)。HTTP-date 形式 (RFC 7231)。 */
    private static final String SUNSET_DATE = "Wed, 31 Dec 2026 23:59:59 GMT";

    private final AdMessagingCampaignTransitionService transitionService;
    private final AccessControlService accessControlService;

    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
    }

    private void writeDeprecationHeaders(HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", SUNSET_DATE);
        response.setHeader(
                "Link",
                "</api/v1/organizations/{organizationId}/advertiser/campaigns/messaging>; "
                        + "rel=\"successor-version\"");
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<CampaignDetailResponse> submit(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.submit(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CampaignDetailResponse> cancel(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.cancel(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    @PostMapping("/{id}/launch")
    public ApiResponse<CampaignDetailResponse> launch(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.launch(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<CampaignDetailResponse> pause(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.pause(id, ScopeType.ORGANIZATION, organizationId, userId));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<CampaignDetailResponse> resume(
            @PathVariable UUID id,
            @RequestParam Long organizationId,
            HttpServletResponse response) {
        writeDeprecationHeaders(response);
        verifyOrganizationAccess(organizationId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionService.resume(id, ScopeType.ORGANIZATION, organizationId, userId));
    }
}
