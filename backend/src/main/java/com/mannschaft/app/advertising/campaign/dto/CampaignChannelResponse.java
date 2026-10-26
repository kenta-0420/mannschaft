package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 キャンペーンチャネルレスポンス。
 */
public record CampaignChannelResponse(
        UUID id,
        UUID campaignId,
        AdChannelType channelType,
        String locale,
        String subject,
        String bodyMarkdown,
        String imageUrl,
        String ctaLabel,
        String ctaUrl,
        Long bannerCreativeId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
