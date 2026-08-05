package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 Phase 11-c キャンペーン配信 claim（週内・同一ユーザーへの二重配信防止）。
 *
 * <p>{@code (campaign_id, user_id, week_start)} の一意制約が「先に場所を取ってから配る」
 * (claim-then-act) の根拠。{@code week_start} の定義は {@link com.mannschaft.app.advertising.campaign.service.AdFrequencyCapService}
 * の週開始（受信者 TZ の月曜 00:00）と厳密に一致させる。定義がずれると Valkey の
 * フリークエンシーキャップと DB の claim が食い違い、二重の守りが機能しなくなる。</p>
 *
 * <p>本 Entity は claim の存在確認・削除にのみ使う（更新は行わない）。実際の INSERT は
 * {@link com.mannschaft.app.advertising.campaign.repository.AdCampaignDeliveryClaimRepository#tryClaim}
 * のネイティブ {@code INSERT IGNORE} を経由し、影響行数で成否を判定する（例外を使わない）。</p>
 */
@Entity
@Table(name = "ad_campaign_delivery_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdCampaignDeliveryClaim extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 消費週の月曜（受信者 TZ）。{@code AdFrequencyCapService#currentWeekStart} と同一定義。 */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
