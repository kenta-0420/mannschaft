package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * <p>一意制約は Flyway DDL（本番スキーマ）に加え、{@code @Table(uniqueConstraints=...)} でも
 * 明示する。test プロファイルのスキーマは Flyway ではなく Hibernate {@code ddl-auto=create} が
 * この Entity 定義から生成するため、Flyway 側にしか制約を書かないと結合テストで一意制約が
 * 存在しないまま実行され、claim-then-act の並行安全性を偽陽性で「検証できてしまう」。</p>
 *
 * <p>本 Entity は更新を行わない（作成・存在確認・削除のみ）。実際の claim 確保は
 * {@link com.mannschaft.app.advertising.campaign.service.AdCampaignDeliveryClaimService#tryClaim}
 * が {@code saveAndFlush} でこの Entity を保存し、一意制約違反（{@code DataIntegrityViolationException}）の
 * 有無で成否を判定する（{@code REQUIRES_NEW} の専用トランザクション内で捕捉するため、
 * 衝突しても呼び出し元のトランザクションは巻き込まれない）。</p>
 */
@Entity
@Table(name = "ad_campaign_delivery_claims",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_acdc_campaign_user_week",
                columnNames = {"campaign_id", "user_id", "week_start"}))
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
