package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 会員（受益者）単位の継続課金エンティティ（membership_subscriptions）。
 *
 * <p>ガワだけの {@code team_subscriptions}(V9.055) とは別物で、<b>受益者ごとの継続課金</b>を表す。
 * Stripe Subscription（destination charge）と 1:1 対応し、各サイクルの invoice 固定手数料上書きは
 * 焼き付けた {@link #feePolicyKey} で算出する（遡及防止・README §4.2）。</p>
 *
 * <p>状態遷移（02_api_design.md §4.2）:</p>
 * <pre>
 * PENDING ──(初回 invoice.paid)──▶ ACTIVE
 * ACTIVE ──(invoice.payment_failed)──▶ PAST_DUE ──(再試行 invoice.paid)──▶ ACTIVE
 * ACTIVE/PAST_DUE ──(期末解約)──▶ 期末に CANCELLED
 * </pre>
 * 状態遷移メソッドは本 Entity に置き、不正遷移はガードして {@link IllegalStateException} を投げる
 * （ドメイン不変条件の自己防御・P7 {@link PaymentRequestEntity} の流儀を踏襲しつつ不正遷移は明示拒否）。
 * {@code cancel_at_period_end} / {@code skip_until}（今月スキップ）は ACTIVE 内の利用者操作で status とは独立。
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（user/team/org/connect_account/payment_item はすべて論理参照）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: organization_id を持つため {@code AbstractTenantAwareRepository} 継承対象。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.1 / 02_api_design.md §4</p>
 */
@Entity
@Table(name = "membership_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MembershipSubscriptionEntity extends UuidV7Entity {

    /** テナント（シャードキー候補）。論理参照・FK なし。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 対象会費項目。論理参照・FK なし。 */
    @Column(name = "payment_item_id", nullable = false)
    private Long paymentItemId;

    /** 受益者（会員）。論理参照・FK なし。ペイウォール・所属判定キー。 */
    @Column(name = "beneficiary_user_id", nullable = false)
    private Long beneficiaryUserId;

    /** 払い手。論理参照・FK なし。 */
    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    /** 第三者代理払いの権原 payment_proxy_grants.id。論理参照・FK なし。 */
    @Column(name = "payment_proxy_grant_id")
    private UUID paymentProxyGrantId;

    /** 受領主体の種別（TEAM/ORG）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private ScopeKind scopeKind;

    /** 受領主体 ID（team_id/org_id）。論理参照・FK なし。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 受領 Connect 口座 connect_accounts.id。論理参照・FK なし。 */
    @Column(name = "payee_connect_account_id", nullable = false)
    private UUID payeeConnectAccountId;

    /** Stripe Subscription ID (sub_xxx)。退避策（自前バッチ）採用時は NULL。UNIQUE。 */
    @Column(name = "stripe_subscription_id", length = 64)
    private String stripeSubscriptionId;

    /** 払い手の platform Customer ID (cus_xxx)。 */
    @Column(name = "stripe_customer_id", length = 64)
    private String stripeCustomerId;

    /** 課金周期（MONTHLY/YEARLY）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 8)
    private BillingInterval billingInterval;

    /** ユーザ指定決済日（1-28 等）。 */
    @Column(name = "billing_anchor_day")
    private Short billingAnchorDay;

    /** 状態。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private MembershipSubscriptionStatus status = MembershipSubscriptionStatus.PENDING;

    /** 加入時に解決した手数料パターン（遡及防止の焼き付け・F22.1 fee_policies）。 */
    @Column(name = "fee_policy_key", nullable = false, length = 40)
    @Builder.Default
    private String feePolicyKey = "DEFAULT";

    /** 額面（円整数・加入時に固定＝price-lock）。 */
    @Column(name = "face_amount", nullable = false)
    private Integer faceAmount;

    /** 通貨（加入時に固定）。 */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    /** 現サイクル開始日。 */
    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;

    /** 現サイクル終了日（= 受益者の valid_until 同期）。 */
    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    /** 期末解約フラグ（ACTIVE 内の利用者操作・status と独立）。 */
    @Column(name = "cancel_at_period_end", nullable = false)
    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;

    /** CANCELLED 遷移日時。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 今月スキップ（pause_collection resumes_at）。NULL=スキップなし（README §4.5）。 */
    @Column(name = "skip_until")
    private LocalDate skipUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（GDPR/退会）。業務状態（status）とは独立。NULL=有効。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 状態遷移（ガード付き・不正遷移は IllegalStateException）。後続波の subscribe/Webhook が利用する。
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 初回 invoice.paid で PENDING → ACTIVE に遷移する。
     *
     * @param periodStart 現サイクル開始日
     * @param periodEnd   現サイクル終了日（受益者 valid_until 同期）
     * @throws IllegalStateException PENDING 以外から呼んだ場合
     */
    public void markActive(LocalDate periodStart, LocalDate periodEnd) {
        if (this.status != MembershipSubscriptionStatus.PENDING) {
            throw new IllegalStateException(
                    "PENDING からのみ ACTIVE に遷移できます（現状態=" + this.status + "）");
        }
        this.status = MembershipSubscriptionStatus.ACTIVE;
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
    }

    /**
     * 支払い失敗（invoice.payment_failed）で ACTIVE → PAST_DUE に遷移する。
     *
     * @throws IllegalStateException ACTIVE 以外から呼んだ場合
     */
    public void markPastDue() {
        if (this.status != MembershipSubscriptionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "ACTIVE からのみ PAST_DUE に遷移できます（現状態=" + this.status + "）");
        }
        this.status = MembershipSubscriptionStatus.PAST_DUE;
    }

    /**
     * 再試行成功（invoice.paid）で PAST_DUE → ACTIVE に復帰し、サイクルを 1 期延長する。
     *
     * @param periodStart 現サイクル開始日
     * @param periodEnd   現サイクル終了日（受益者 valid_until を 1 サイクル延長）
     * @throws IllegalStateException PAST_DUE 以外から呼んだ場合
     */
    public void markRecovered(LocalDate periodStart, LocalDate periodEnd) {
        if (this.status != MembershipSubscriptionStatus.PAST_DUE) {
            throw new IllegalStateException(
                    "PAST_DUE からのみ ACTIVE に復帰できます（現状態=" + this.status + "）");
        }
        this.status = MembershipSubscriptionStatus.ACTIVE;
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
    }

    /**
     * 解約（期末到達 or Stripe subscription.deleted）で CANCELLED に遷移する。
     *
     * <p>ACTIVE/PAST_DUE/PENDING から遷移可。既に CANCELLED/EXPIRED の場合は不正遷移。</p>
     *
     * @throws IllegalStateException 既に CANCELLED/EXPIRED の場合
     */
    public void markCancelled() {
        if (this.status == MembershipSubscriptionStatus.CANCELLED
                || this.status == MembershipSubscriptionStatus.EXPIRED) {
            throw new IllegalStateException(
                    "終端状態からは CANCELLED に遷移できません（現状態=" + this.status + "）");
        }
        this.status = MembershipSubscriptionStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * 期末解約を予約する（cancel_at_period_end=true）。期末まで利用可・日割り返金なし・期末前は再有効化可。
     *
     * <p>ACTIVE/PAST_DUE のみ予約可（02_api §4.1）。それ以外（PENDING/CANCELLED/EXPIRED）は不正。</p>
     *
     * @throws IllegalStateException ACTIVE/PAST_DUE 以外から呼んだ場合
     */
    public void scheduleCancelAtPeriodEnd() {
        if (this.status != MembershipSubscriptionStatus.ACTIVE
                && this.status != MembershipSubscriptionStatus.PAST_DUE) {
            throw new IllegalStateException(
                    "ACTIVE/PAST_DUE のみ期末解約を予約できます（現状態=" + this.status + "）");
        }
        this.cancelAtPeriodEnd = true;
    }

    /**
     * 期末解約の予約を取り消す（再有効化・期末前のみ）。
     *
     * @throws IllegalStateException 期末解約が予約されていない場合
     */
    public void clearCancelAtPeriodEnd() {
        if (!Boolean.TRUE.equals(this.cancelAtPeriodEnd)) {
            throw new IllegalStateException("期末解約が予約されていません");
        }
        this.cancelAtPeriodEnd = false;
    }

    /**
     * 今月スキップを適用する（pause_collection void・README §4.5）。
     *
     * <p>ACTIVE のみスキップ可（02_api §4.3）。既に skip_until がセット済なら不正（二重スキップ防止）。
     * status は ACTIVE のまま（解約とは独立）。</p>
     *
     * @param resumesAt 再開予定日（pause_collection resumes_at）
     * @throws IllegalStateException ACTIVE 以外 / 既にスキップ済の場合
     */
    public void applySkipUntil(LocalDate resumesAt) {
        if (this.status != MembershipSubscriptionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "ACTIVE のみ今月スキップできます（現状態=" + this.status + "）");
        }
        if (this.skipUntil != null) {
            throw new IllegalStateException("既に今月スキップが適用されています（skipUntil=" + this.skipUntil + "）");
        }
        this.skipUntil = resumesAt;
    }

    /**
     * 今月スキップを解除する（pause_collection 解除・再開）。
     *
     * @throws IllegalStateException スキップが適用されていない場合
     */
    public void clearSkip() {
        if (this.skipUntil == null) {
            throw new IllegalStateException("今月スキップが適用されていません");
        }
        this.skipUntil = null;
    }

    /**
     * Stripe Subscription / Customer の ID を焼き付ける（subscribe 確定時・後続波で利用）。
     *
     * @param stripeSubscriptionId Stripe Subscription ID（sub_xxx）
     * @param stripeCustomerId     払い手の platform Customer ID（cus_xxx）
     */
    public void linkStripeIds(String stripeSubscriptionId, String stripeCustomerId) {
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.stripeCustomerId = stripeCustomerId;
    }
}
