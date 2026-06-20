package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * F09.17 キャンペーンチャネル別本文・件名・クリエイティブ。
 * 1 キャンペーン × 4 チャネル × N 言語の組み合わせを格納する。
 */
@Entity
@Table(name = "ad_messaging_campaign_channels")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdMessagingCampaignChannel extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private AdChannelType channelType;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body_markdown", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String bodyMarkdown;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "cta_label", length = 50)
    private String ctaLabel;

    @Column(name = "cta_url", length = 500)
    private String ctaUrl;

    /** F09.7 ads.id (BANNER 時のみ・FK なし) */
    @Column(name = "banner_creative_id")
    private Long bannerCreativeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.locale == null) {
            this.locale = "ja";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
