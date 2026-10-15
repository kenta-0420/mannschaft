package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 Phase 11-a SYSTEM_ADMIN 審査キュー要素レスポンス DTO。
 *
 * <p>{@code moderation_status} が {@code PENDING} または {@code AUTO_FLAGGED} の
 * メッセージ型キャンペーン 1 件分を表す。</p>
 *
 * <p>F09.17 Phase 11-e で {@code organizationId} を削除し、scope ベース化完了。</p>
 */
@Getter
@Builder
public class ReviewQueueItemResponse {

    /** キャンペーン ID (UUID v7) */
    private final UUID campaignId;

    /** F09.17 Phase 11-d-2: スコープ種別 (ORGANIZATION / TEAM)。 */
    private final ScopeType scopeType;

    /** F09.17 Phase 11-d-2: スコープ ID (organization_id または team_id)。 */
    private final Long scopeId;

    /** 広告主アカウント ID */
    private final Long advertiserAccountId;

    /** キャンペーン名 */
    private final String name;

    /** キャンペーン状態 */
    private final AdCampaignStatus status;

    /** 審査状態 */
    private final AdModerationStatus moderationStatus;

    /** 作成日時 */
    private final LocalDateTime createdAt;

    /**
     * Entity から DTO に変換するヘルパー。
     */
    public static ReviewQueueItemResponse from(AdMessagingCampaign campaign) {
        return ReviewQueueItemResponse.builder()
                .campaignId(campaign.getId())
                .scopeType(campaign.getScopeType())
                .scopeId(campaign.getScopeId())
                .advertiserAccountId(campaign.getAdvertiserAccountId())
                .name(campaign.getName())
                .status(campaign.getStatus())
                .moderationStatus(campaign.getModerationStatus())
                .createdAt(campaign.getCreatedAt())
                .build();
    }
}
