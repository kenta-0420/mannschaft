package com.mannschaft.app.advertising.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 広告インプレッションエンティティ。
 * ad_impressions テーブルに対応する不変なイベントレコード。
 */
@Entity
@Table(name = "ad_impressions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdImpressionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long adId;

    /**
     * F09.7 の ad_campaigns.id（Long）。F09.17 メッセージキャンペーン用ルートでは NULL。
     * F09.17 用は {@link #messagingCampaignId} を使うこと。
     */
    @Column(nullable = true)
    private Long campaignId;

    /**
     * F09.17 の ad_messaging_campaigns.id（UUID）。F09.7 用ルートでは NULL。
     * F09.7 用は {@link #campaignId} を使うこと。
     */
    @Column(name = "messaging_campaign_id", columnDefinition = "BINARY(16)")
    private UUID messagingCampaignId;

    /** 未ログインユーザーのインプレッションは NULL */
    @Column
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = LocalDateTime.now();
        }
    }

    /**
     * F09.7 用インプレッション記録を生成するファクトリメソッド。
     *
     * @param adId       広告ID (ads.id)
     * @param campaignId F09.7 キャンペーンID (ad_campaigns.id)
     * @param userId     ユーザーID（未ログインの場合は null）
     * @return 新規 AdImpressionEntity
     */
    public static AdImpressionEntity create(Long adId, Long campaignId, Long userId) {
        return AdImpressionEntity.builder()
                .adId(adId)
                .campaignId(campaignId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /**
     * F09.17 メッセージキャンペーン用インプレッション記録を生成するファクトリメソッド。
     *
     * @param adId                 広告ID (ads.id)
     * @param messagingCampaignId  F09.17 メッセージキャンペーンID (ad_messaging_campaigns.id, UUID)
     * @param userId               ユーザーID（未ログインの場合は null）
     * @return 新規 AdImpressionEntity
     */
    public static AdImpressionEntity createForMessagingCampaign(Long adId, UUID messagingCampaignId, Long userId) {
        return AdImpressionEntity.builder()
                .adId(adId)
                .campaignId(null)
                .messagingCampaignId(messagingCampaignId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
