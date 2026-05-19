package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.ReviewQueueItemResponse;
import com.mannschaft.app.advertising.campaign.dto.UnblockCampaignRequest;
import com.mannschaft.app.advertising.campaign.service.AdCampaignModerationService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F09.17 Phase 11-a SYSTEM_ADMIN メッセージ型キャンペーン審査コントローラー。
 *
 * <p>提供 API:
 * <ul>
 *   <li>{@code GET /review-queue} 審査待ちキャンペーン一覧</li>
 *   <li>{@code POST /{id}/approve} キャンペーン承認</li>
 *   <li>{@code POST /{id}/block} キャンペーンブロック</li>
 *   <li>{@code POST /{id}/unblock} キャンペーン UNBLOCK (BLOCKED→REVIEW 戻し)</li>
 * </ul>
 * クラスレベル {@code @PreAuthorize} で SYSTEM_ADMIN ロールに限定する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/ad-campaigns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminAdCampaignController {

    private final AdCampaignModerationService adCampaignModerationService;

    /**
     * SYSTEM_ADMIN 審査キューを取得する。
     *
     * <p>{@code moderation_status IN (PENDING, AUTO_FLAGGED)} のキャンペーンを古い順に返す。</p>
     */
    @GetMapping("/review-queue")
    public PagedResponse<ReviewQueueItemResponse> getReviewQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReviewQueueItemResponse> pageResult = adCampaignModerationService.getReviewQueue(page, size);
        return PagedResponse.of(
                pageResult.getContent(),
                new PagedResponse.PageMeta(
                        pageResult.getTotalElements(),
                        pageResult.getNumber(),
                        pageResult.getSize(),
                        pageResult.getTotalPages()
                )
        );
    }

    /**
     * キャンペーンを承認する。
     */
    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable("id") UUID campaignId) {
        Long moderatorUserId = SecurityUtils.getCurrentUserId();
        adCampaignModerationService.approve(campaignId, moderatorUserId);
    }

    /**
     * キャンペーンをブロックする。
     */
    @PostMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(
            @PathVariable("id") UUID campaignId,
            @Valid @RequestBody BlockCampaignRequest request) {
        Long moderatorUserId = SecurityUtils.getCurrentUserId();
        adCampaignModerationService.block(campaignId, moderatorUserId, request);
    }

    /**
     * 誤 BLOCK されたキャンペーンを UNBLOCK して再審査キューに戻す (F09.17 残課題 3)。
     *
     * <p>遷移: {@code BLOCKED → REVIEW}。{@code reason} は監査用必須。
     * {@code status != BLOCKED} の場合は {@code AD_CAMPAIGN_NOT_UNBLOCKABLE} で 400 を返す。</p>
     */
    @PostMapping("/{id}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(
            @PathVariable("id") UUID campaignId,
            @Valid @RequestBody UnblockCampaignRequest request) {
        Long moderatorUserId = SecurityUtils.getCurrentUserId();
        adCampaignModerationService.unblock(campaignId, moderatorUserId, request);
    }
}
