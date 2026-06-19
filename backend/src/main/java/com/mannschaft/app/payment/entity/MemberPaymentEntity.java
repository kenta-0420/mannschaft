package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支払い記録エンティティ。Stripe 自動決済または ADMIN 手動記録による支払い情報を管理する。
 */
@PersonalData(category = "payments")
@Entity
@Table(name = "member_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MemberPaymentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long paymentItemId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountPaid;

    @Column(nullable = false, length = 3)
    @SuperBuilder.Default
    private String currency = "JPY";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @SuperBuilder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    private LocalDate validFrom;

    private LocalDate validUntil;

    @Column(length = 100)
    private String stripeCheckoutSessionId;

    @Column(length = 100)
    private String stripePaymentIntentId;

    private LocalDateTime paidAt;

    private Long recordedBy;

    @Column(length = 500)
    private String note;

    @Column(length = 100)
    private String stripeRefundId;

    @Column(length = 512)
    private String stripeReceiptUrl;

    private LocalDateTime refundedAt;

    // === F08.9 P1 Wave2: 払い手分離・money rail 連結列（V74.001 追加列）===

    /**
     * 払い手ユーザーID（実際に決済した人）。
     * 受益者（userId）と同一の場合は SELF を示す。論理参照・FK なし。
     * NULL は手動記録の移行期のみ許容、新規作成時は必須とする。
     */
    private Long payerUserId;

    /**
     * 第三者代理払いの権原 payment_proxy_grants.id（BINARY(16) = UUID）。
     * 保護者経由の代理払いは NULL（権原は parental_consent_links 参照）。
     * PayerRelationship=PROXY_GRANT の場合のみ設定される。
     */
    @Column(columnDefinition = "BINARY(16)")
    private UUID paymentProxyGrantId;

    /**
     * 払い手と受益者の関係スナップショット。
     * 監査・表示用。支払い後に関係が変わっても記録は変更しない。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PayerRelationship payerRelationship;

    /**
     * F22.1 money rail 連結: escrow_transactions.id（BINARY(16) = UUID）。
     * Connect 決済時に設定。手動記録は NULL。
     */
    @Column(columnDefinition = "BINARY(16)")
    private UUID escrowTransactionId;

    /**
     * 継続課金の親サブスクリプション membership_subscriptions.id（BINARY(16) = UUID）。
     * 継続課金由来の支払いのみ設定。手動/単発は NULL。
     */
    @Column(columnDefinition = "BINARY(16)")
    private UUID membershipSubscriptionId;

    /**
     * F08.9 P1 Wave4: Connect 即時 charge による会費 PENDING 起票時の PAID 反映（escrow CAPTURED 連動）。
     *
     * <p>{@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener} が
     * {@code escrow_transactions} の CAPTURED を受けて呼ぶ。冪等性は呼び出し側が PENDING の場合のみ呼ぶことで担保する
     * （既に PAID なら no-op）。Stripe Checkout 経路の {@link #markAsPaid} と異なり、金額・支払い方法は
     * PENDING 起票時の値を保持し（払い手が支払った額＝起票額）、有効期間（validFrom/validUntil）のみ確定設定する。</p>
     *
     * @param validFrom 有効期間開始（通常 CAPTURED 確定日）
     * @param validUntil 有効期間終了（payment_item.type 別に算出・ITEM/DONATION は null）
     */
    public void markAsPaidByEscrowCapture(LocalDate validFrom, LocalDate validUntil) {
        this.status = PaymentStatus.PAID;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * Stripe Checkout セッション完了時に支払い状態を更新する。
     */
    public void markAsPaid(String stripePaymentIntentId, BigDecimal amountPaid,
                           LocalDate validFrom, LocalDate validUntil,
                           String stripeReceiptUrl) {
        this.status = PaymentStatus.PAID;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.amountPaid = amountPaid;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.stripeReceiptUrl = stripeReceiptUrl;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * Checkout セッション期限切れ時にキャンセル状態にする。
     */
    public void markAsCancelled() {
        this.status = PaymentStatus.CANCELLED;
    }

    /**
     * 全額返金時に返金状態にする。
     */
    public void markAsRefunded(String stripeRefundId) {
        this.status = PaymentStatus.REFUNDED;
        this.stripeRefundId = stripeRefundId;
        this.refundedAt = LocalDateTime.now();
    }

    /**
     * 手動記録の修正を行う。
     */
    public void updateManualPayment(BigDecimal amountPaid, LocalDate validFrom,
                                    LocalDate validUntil, String note) {
        if (amountPaid != null) this.amountPaid = amountPaid;
        if (validFrom != null) this.validFrom = validFrom;
        if (validUntil != null) this.validUntil = validUntil;
        if (note != null) this.note = note;
    }

    /**
     * Stripe Checkout セッション ID を設定する。
     */
    public void setStripeCheckoutSessionId(String sessionId) {
        this.stripeCheckoutSessionId = sessionId;
    }
}
