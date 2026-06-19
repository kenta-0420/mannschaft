package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 お知らせチャネル配信実績。
 * 退会時は user_id を NULL 化、campaign_id 集計値は保持する (CLAUDE.md 原則 4)。
 */
@Entity
@Table(name = "ad_announcement_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdAnnouncementDelivery extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** 受信者 (退会時 NULL 化・FK なし) */
    @Column(name = "user_id")
    private Long userId;

    /** F02.6 announcement_feeds.id (FK なし) */
    @Column(name = "announcement_feed_id", nullable = false)
    private Long announcementFeedId;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** YYYY-MM 形式 (パーティショニング用) */
    @Column(name = "month_key", nullable = false, length = 7)
    private String monthKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
