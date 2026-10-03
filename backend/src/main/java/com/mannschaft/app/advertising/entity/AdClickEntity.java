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
 * 広告クリックエンティティ。
 * ad_clicks テーブルに対応する不変なイベントレコード。
 */
@Entity
@Table(name = "ad_clicks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdClickEntity {

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

    /** インプレッションなしの直接クリックは NULL */
    @Column
    private Long impressionId;

    /** 未ログインユーザーのクリックは NULL */
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
     * F09.7 用クリック記録を生成するファクトリメソッド。
     *
     * @param adId         広告ID (ads.id)
     * @param campaignId   F09.7 キャンペーンID (ad_campaigns.id)
     * @param impressionId インプレッションID（直接クリックの場合は null）
     * @param userId       ユーザーID（未ログインの場合は null）
     * @return 新規 AdClickEntity
     */
    public static AdClickEntity create(Long adId, Long campaignId, Long impressionId, Long userId) {
        return AdClickEntity.builder()
                .adId(adId)
                .campaignId(campaignId)
                .impressionId(impressionId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /**
     * F09.17 メッセージキャンペーン用クリック記録を生成するファクトリメソッド。
     *
     * @param adId                広告ID (ads.id)
     * @param messagingCampaignId F09.17 メッセージキャンペーンID (ad_messaging_campaigns.id, UUID)
     * @param impressionId        インプレッションID（直接クリックの場合は null）
     * @param userId              ユーザーID（未ログインの場合は null）
     * @return 新規 AdClickEntity
     */
    public static AdClickEntity createForMessagingCampaign(Long adId, UUID messagingCampaignId,
                                                           Long impressionId, Long userId) {
        return AdClickEntity.builder()
                .adId(adId)
                .campaignId(null)
                .messagingCampaignId(messagingCampaignId)
                .impressionId(impressionId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
