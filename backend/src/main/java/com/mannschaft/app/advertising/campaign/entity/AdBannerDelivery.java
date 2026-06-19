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
 * F09.17 バナーチャネル配信実績。
 * F09.7 ad_impressions をポリモーフィック参照 (FK なし)。
 */
@Entity
@Table(name = "ad_banner_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AdBannerDelivery extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** 受信者 (退会時 NULL 化・FK なし) */
    @Column(name = "user_id")
    private Long userId;

    /** F09.7 ad_impressions.id (FK なし) */
    @Column(name = "ad_impression_id", nullable = false)
    private Long adImpressionId;

    @Column(name = "served_at", nullable = false)
    private LocalDateTime servedAt;

    @Column(name = "clicked_at")
    private LocalDateTime clickedAt;

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
