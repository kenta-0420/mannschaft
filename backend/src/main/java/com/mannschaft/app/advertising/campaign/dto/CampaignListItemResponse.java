package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 メッセージ型キャンペーン一覧アイテムレスポンス。
 *
 * <p>設計書 §4 一覧 API のレスポンス仕様に対応。
 * channels / audienceSegments は詳細レスポンスで返す（一覧では省略）。</p>
 */
public record CampaignListItemResponse(
        UUID id,
        String name,
        AdCampaignStatus status,
        AdModerationStatus moderationStatus,
        Long totalBudgetYen,
        Long consumedBudgetYen,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String scheduledTimezone,
        Integer frequencyCapOverride,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
