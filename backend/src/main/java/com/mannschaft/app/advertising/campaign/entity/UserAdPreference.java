package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * F09.17 受信者ごとの広告受信設定。各ユーザー 1 行 (UNIQUE)。
 * 初回広告受信時に明示同意モーダルを表示し {@code consentedAt} を記録。
 */
@Entity
@Table(name = "user_ad_preferences")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class UserAdPreference extends UuidV7Entity {

    /** users.id (FK なし・UNIQUE) */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "accept_announcement_ads", nullable = false)
    private Boolean acceptAnnouncementAds;

    @Column(name = "accept_email_ads", nullable = false)
    private Boolean acceptEmailAds;

    @Column(name = "accept_push_ads", nullable = false)
    private Boolean acceptPushAds;

    @Column(name = "accept_banner_ads", nullable = false)
    private Boolean acceptBannerAds;

    /** ブロック広告主 ID 配列の JSON 文字列 (上限 100 件・Service 層で検証) */
    @Column(name = "blocked_advertiser_account_ids", columnDefinition = "JSON", nullable = false)
    private String blockedAdvertiserAccountIds;

    /** unsubscribe JWT バージョン (インクリメントで一括失効) */
    @Column(name = "unsubscribe_token_version", nullable = false)
    private Integer unsubscribeTokenVersion;

    @Column(name = "consented_at")
    private LocalDateTime consentedAt;

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
        if (this.acceptAnnouncementAds == null) {
            this.acceptAnnouncementAds = Boolean.TRUE;
        }
        if (this.acceptEmailAds == null) {
            this.acceptEmailAds = Boolean.TRUE;
        }
        if (this.acceptPushAds == null) {
            this.acceptPushAds = Boolean.TRUE;
        }
        if (this.acceptBannerAds == null) {
            this.acceptBannerAds = Boolean.TRUE;
        }
        if (this.blockedAdvertiserAccountIds == null) {
            this.blockedAdvertiserAccountIds = "[]";
        }
        if (this.unsubscribeTokenVersion == null) {
            this.unsubscribeTokenVersion = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
