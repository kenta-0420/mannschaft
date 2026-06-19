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
 * F09.17 プッシュ通知チャネル配信実績。
 * 24 時間以内再試行、それ以降失効 (設計書 §11 解決事項 9)。
 */
@Entity
@Table(name = "ad_push_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AdPushDelivery extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** 受信者 (退会時 NULL 化・FK なし) */
    @Column(name = "user_id")
    private Long userId;

    /** F04.3 notifications.id (FK なし) */
    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    @Column(name = "tapped_at")
    private LocalDateTime tappedAt;

    @Column(name = "failed_reason", length = 100)
    private String failedReason;

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
