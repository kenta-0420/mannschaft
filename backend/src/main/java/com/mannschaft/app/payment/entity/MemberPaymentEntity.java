package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支払い記録エンティティ。Stripe 自動決済または ADMIN 手動記録による支払い情報を管理する。
 */
@PersonalData(category = "payments")
@Entity
@Table(name = "member_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberPaymentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long paymentItemId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountPaid;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
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
