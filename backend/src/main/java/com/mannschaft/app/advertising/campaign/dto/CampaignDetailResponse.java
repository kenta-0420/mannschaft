package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 メッセージ型キャンペーン詳細レスポンス。
 * 一覧アイテムにチャネル一覧とターゲティング条件を加えた完全な構造。
 */
public record CampaignDetailResponse(
        UUID id,
        Long advertiserAccountId,
        String name,
        AdCampaignStatus status,
        AdModerationStatus moderationStatus,
        String blockedReason,
        Long totalBudgetYen,
        Long consumedBudgetYen,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String scheduledTimezone,
        Integer frequencyCapOverride,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CampaignChannelResponse> channels,
        List<AudienceSegmentResponse> audienceSegments
) {
}
