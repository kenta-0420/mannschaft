package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * F09.17 キャンペーン審査履歴・自動 NG 検知ログ。保持期間 3 年。
 * 自動検知の場合 {@code moderatorUserId} は NULL。
 */
@Entity
@Table(name = "ad_campaign_moderation_logs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdCampaignModerationLog extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** レビュアー user_id (自動検知は NULL・FK なし) */
    @Column(name = "moderator_user_id")
    private Long moderatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private AdModerationAction action;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 検知された NG ワード配列 JSON */
    @Column(name = "ng_words_detected", columnDefinition = "JSON")
    private String ngWordsDetected;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
