package com.mannschaft.app.notification.credit.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通知プリペイドクレジット購入履歴エンティティ。
 *
 * <p>Stripe Checkout Session を通じた一括購入を記録する。
 * 冪等処理のために {@code idempotency_key} を UNIQUE 制約で管理する。</p>
 */
@Entity
@Table(name = "notification_credit_purchases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class NotificationCreditPurchaseEntity extends BaseEntity {

    /** 購入した組織ID */
    @Column(nullable = false)
    private Long organizationId;

    /** 購入パッケージID（マスタへの参照） */
    @Column(nullable = false)
    private Long packageId;

    /** 購入操作者のユーザーID */
    @Column(nullable = false)
    private Long purchasedByUserId;

    /** 購入時点での付与通数（スナップショット） */
    @Column(nullable = false)
    private Long creditsGranted;

    /** FIFO消費追跡用残クレジット */
    @Column(nullable = false)
    private Long remainingCredits;

    /** 購入時点での日本円価格（スナップショット） */
    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal priceJpy;

    /** Stripe Checkout Session ID */
    @Column(length = 200)
    private String stripeCheckoutSessionId;

    /** Stripe Payment Intent ID */
    @Column(length = 200)
    private String stripePaymentIntentId;

    /** 決済ステータス */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NotificationCreditPurchaseStatus paymentStatus = NotificationCreditPurchaseStatus.PENDING;

    /** 決済完了日時 */
    private LocalDateTime paidAt;

    /** Stripe レシートURL */
    @Column(length = 500)
    private String receiptUrl;

    /** 有効期限（paidAt + 2年） */
    private LocalDateTime expiresAt;

    /** 有効期限30日前アラート送信済みフラグ */
    @Column(nullable = false)
    @Builder.Default
    private Boolean alertSent30d = false;

    /** 有効期限7日前アラート送信済みフラグ */
    @Column(nullable = false)
    @Builder.Default
    private Boolean alertSent7d = false;

    /** 失効処理実施日時（失効バッチが処理した際にセット） */
    private LocalDateTime expiredAt;

    /** Webhook冪等キー（UUID文字列）。UNIQUE制約で二重処理を防ぐ */
    @Column(length = 100, unique = true)
    private String idempotencyKey;

    /**
     * 決済完了時に状態を更新する。
     *
     * @param paymentIntentId Stripe Payment Intent ID
     * @param receiptUrl      Stripe レシートURL
     */
    public void markAsPaid(String paymentIntentId, String receiptUrl) {
        this.paymentStatus = NotificationCreditPurchaseStatus.PAID;
        this.stripePaymentIntentId = paymentIntentId;
        this.receiptUrl = receiptUrl;
        this.paidAt = LocalDateTime.now();
        this.expiresAt = this.paidAt.plusYears(2);
        this.remainingCredits = this.creditsGranted;
    }

    /**
     * Stripe Checkout Session ID を割り当てる（Checkout 作成後）。
     *
     * <p>{@code createCheckout} 内で save 済み（id 採番済み）の本エンティティに対し、
     * Stripe Session 作成後に session id を後付けする更新メソッド。managed entity を
     * その場でミューテートし、続く {@code save} を同一行 UPDATE にする。</p>
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * 本エンティティは {@code @Builder(toBuilder = true)}（{@code @SuperBuilder} ではない）で、
     * 主キー {@code id} は基底クラス {@link BaseEntity} のフィールドである。
     * {@code toBuilder()} は継承フィールド {@code id} を引き継がず {@code id = null} の
     * 新インスタンスになり、{@code save} が UPDATE でなく INSERT を実行する。これは
     * {@code idempotency_key} の UNIQUE 制約違反（同じキーで2行目を INSERT）で 500 になる
     * 二重 save 構造であった。よって直接ミューテートして UPDATE にする。</p>
     *
     * @param checkoutSessionId Stripe Checkout Session ID
     */
    public void assignCheckoutSession(String checkoutSessionId) {
        this.stripeCheckoutSessionId = checkoutSessionId;
    }

    /**
     * クレジットを消費する（FIFO）。
     *
     * @param amount 消費するクレジット数
     */
    public void consumeCredits(long amount) {
        this.remainingCredits -= amount;
    }

    /**
     * 30日前アラート送信済みとしてマークする。
     */
    public void markAlertSent30d() {
        this.alertSent30d = true;
    }

    /**
     * 7日前アラート送信済みとしてマークする。
     */
    public void markAlertSent7d() {
        this.alertSent7d = true;
    }

    /**
     * 失効処理を実施する（残クレジットをゼロにする）。
     */
    public void markExpired() {
        this.expiredAt = LocalDateTime.now();
        this.remainingCredits = 0L;
    }
}
