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

    /**
     * advertiser_accounts.id（同一 advertising ドメインのため FK 可）。
     * F09.19.5 で advertiser_organization_id 直結から付け替え（scope 化。org/team 両対応）。
     */
    @Column(nullable = false)
    private Long advertiserAccountId;

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

    /** 通報 3 件による自動停止時刻（F09.19.9）。非 NULL 中は広告主 resume を AD_033 で拒否する。 */
    private java.time.LocalDateTime reportSuspendedAt;

    /**
     * 通報自動停止が ACTIVE→PAUSED 遷移を行った場合 TRUE（F09.19.9・V151）。
     * unsuspend 時に「自動停止で PAUSED になっていた場合のみ ACTIVE 復帰」を判定するために使う。
     * 広告主が自ら pause 済みの campaign が自動停止された場合は FALSE のまま（unsuspend しても ACTIVE 復帰しない）。
     */
    private Boolean reportAutoPaused;

    public enum CampaignStatus {
        DRAFT, PENDING_REVIEW, ACTIVE, PAUSED, ENDED
    }

    // ─── F09.19.1 運用型 CRUD 状態遷移・編集（状態ガードは Service 側で検証済み前提） ───

    /** DRAFT → PENDING_REVIEW。再 submit 時に差戻し理由をクリアする。 */
    public void submitForReview() {
        this.status = CampaignStatus.PENDING_REVIEW;
        this.rejectReason = null;
    }

    /** PENDING_REVIEW → ACTIVE（審査承認）。 */
    public void approve() {
        this.status = CampaignStatus.ACTIVE;
    }

    /** PENDING_REVIEW → DRAFT（審査差戻し）。差戻し理由を永続化する。 */
    public void reject(String reason) {
        this.status = CampaignStatus.DRAFT;
        this.rejectReason = reason;
    }

    public void pause() {
        this.status = CampaignStatus.PAUSED;
    }

    /** PAUSED → ACTIVE。 */
    public void resume() {
        this.status = CampaignStatus.ACTIVE;
    }

    /** ACTIVE / PAUSED → ENDED（終端・不可逆）。 */
    public void end() {
        this.status = CampaignStatus.ENDED;
    }

    /** DRAFT 編集: 全フィールド可。rateCardId 変更時は単価スナップショットを再確定する。 */
    public void applyDraftEdit(String name, PricingModel pricingModel, BigDecimal dailyBudget,
                               LocalDate startDate, LocalDate endDate,
                               Long rateCardId, BigDecimal unitPriceSnapshot) {
        this.name = name;
        this.pricingModel = pricingModel;
        this.dailyBudget = dailyBudget;
        this.startDate = startDate;
        this.endDate = endDate;
        this.rateCardId = rateCardId;
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    /** PAUSED 編集: name / dailyBudget / endDate のみ可。単価スナップショットは不変。 */
    public void applyPausedEdit(String name, BigDecimal dailyBudget, LocalDate endDate) {
        this.name = name;
        this.dailyBudget = dailyBudget;
        this.endDate = endDate;
    }

    // ─── F09.19.9 通報自動停止 / 解除（状態ガード付き。V151） ───

    /**
     * 通報 3 件到達による自動停止を適用する（状態ガード）。
     *
     * <p>{@code report_suspended_at} を記録し、現 status が {@code ACTIVE} の場合のみ {@code PAUSED} へ遷移させて
     * {@code report_auto_paused=TRUE} を立てる（unsuspend の ACTIVE 復帰判定用）。
     * {@code PAUSED}（広告主自身の pause）/ {@code ENDED} / {@code DRAFT} / {@code PENDING_REVIEW} の場合は
     * status を変更しない（ENDED 巻き戻し防御・不可逆不変条件）。冪等性は呼び出し側（既に停止中はスキップ）で担保する。</p>
     *
     * @param suspendedAt 停止時刻（Service の Clock 由来。TZ 一貫性のため引数化）
     */
    public void applyReportAutoStop(java.time.LocalDateTime suspendedAt) {
        this.reportSuspendedAt = suspendedAt;
        if (this.status == CampaignStatus.ACTIVE) {
            this.status = CampaignStatus.PAUSED;
            this.reportAutoPaused = Boolean.TRUE;
        }
    }

    /**
     * 通報自動停止を解除する（SYSTEM_ADMIN の unsuspend。§6.1）。
     *
     * <p>{@code report_suspended_at=NULL} に戻し、自動停止で {@code ACTIVE→PAUSED} 遷移していた場合
     * （{@code report_auto_paused=TRUE}）のみ {@code ACTIVE} へ復帰させてフラグを倒す。
     * 広告主自身が pause 済みだった campaign は PAUSED のまま維持する。</p>
     */
    public void unsuspendFromReport() {
        this.reportSuspendedAt = null;
        if (Boolean.TRUE.equals(this.reportAutoPaused)) {
            this.status = CampaignStatus.ACTIVE;
            this.reportAutoPaused = Boolean.FALSE;
        }
    }
}
