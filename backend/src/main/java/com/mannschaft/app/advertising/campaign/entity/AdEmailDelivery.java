package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdBounceType;
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
 * F09.17 メールチャネル配信実績。
 * 退会時は user_id を NULL 化。バウンス種別で課金可否を判定。
 */
@Entity
@Table(name = "ad_email_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AdEmailDelivery extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** 受信者 (退会時 NULL 化・FK なし) */
    @Column(name = "user_id")
    private Long userId;

    /** F09.6 direct_mail_recipients.id (FK なし) */
    @Column(name = "direct_mail_recipient_id", nullable = false)
    private Long directMailRecipientId;

    /** F09.18 email_outbox.id — 双方向トレース用 (FK なし) */
    @Column(name = "email_outbox_id")
    private UUID emailOutboxId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "bounced_at")
    private LocalDateTime bouncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "bounce_type", length = 20)
    private AdBounceType bounceType;

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
