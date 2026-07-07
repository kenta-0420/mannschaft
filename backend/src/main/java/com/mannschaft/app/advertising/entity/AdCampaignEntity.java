package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 広告キャンペーンエンティティ。
 */
@Entity
@Table(name = "ad_campaigns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdCampaignEntity extends BaseEntity {

    @Column(nullable = false)
    private Long advertiserOrganizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PricingModel pricingModel;

    private BigDecimal dailyBudget;

    private Integer dailyImpressionLimit;

    private LocalDate startDate;

    private LocalDate endDate;

    // ─── F09.19.1 運用型 CRUD 対応列（V144.002。骨格 — 業務ロジックは出陣で実装） ───

    /** ad_rate_cards.id（同一 advertising ドメインのため FK 可）。 */
    private Long rateCardId;

    /** 申込時単価スナップショット（円）。作成時に確定・DRAFT の rateCardId 変更時のみ再確定。 */
    @Column(precision = 10, scale = 4)
    private BigDecimal unitPriceSnapshot;

    /** 直近の審査差戻し理由。reject 時 SET・再 submit 時 NULL クリア。 */
    @Column(length = 500)
    private String rejectReason;

    /** 通報 3 件による自動停止時刻（F09.19.9 実装まで常に NULL）。 */
    private java.time.LocalDateTime reportSuspendedAt;

    public enum CampaignStatus {
        DRAFT, PENDING_REVIEW, ACTIVE, PAUSED, ENDED
    }

    public void pause() {
        this.status = CampaignStatus.PAUSED;
    }
}
