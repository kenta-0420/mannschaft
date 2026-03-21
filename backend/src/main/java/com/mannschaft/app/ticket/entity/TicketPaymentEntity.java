package com.mannschaft.app.ticket.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.ticket.PaymentMethod;
import com.mannschaft.app.ticket.PaymentStatus;
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

import java.time.LocalDateTime;

/**
 * 回数券決済記録エンティティ。Stripe 決済と現地決済の両方を統一管理する。
 */
@Entity
@Table(name = "ticket_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TicketPaymentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(nullable = false)
    private Integer amount;

    @Column(length = 200)
    private String stripeCheckoutSessionId;

    @Column(length = 200)
    private String stripePaymentIntentId;

    @Column(length = 200)
    private String stripeRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    private Integer refundAmount;

    private Long recordedBy;

    private LocalDateTime paidAt;

    private LocalDateTime webhookReceivedAt;

    @Column(length = 500)
    private String note;

    /**
     * 支払い完了に遷移する。
     *
     * @param stripePaymentIntentId Stripe PaymentIntent ID（Stripe 決済の場合）
     */
    public void markAsPaid(String stripePaymentIntentId) {
        this.status = PaymentStatus.PAID;
        this.paidAt = LocalDateTime.now();
        this.stripePaymentIntentId = stripePaymentIntentId;
    }

    /**
     * 現地決済として即座に支払い完了にする。
     */
    public void markAsPaidOnSite() {
        this.status = PaymentStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * 全額返金に遷移する。
     *
     * @param stripeRefundId Stripe Refund ID（Stripe 決済の場合。現地決済は null）
     */
    public void markAsRefunded(String stripeRefundId) {
        this.status = PaymentStatus.REFUNDED;
        this.refundAmount = this.amount;
        this.stripeRefundId = stripeRefundId;
    }

    /**
     * 部分返金に遷移する。
     *
     * @param refundAmount   返金額
     * @param stripeRefundId Stripe Refund ID（Stripe 決済の場合。現地決済は null）
     */
    public void markAsPartiallyRefunded(int refundAmount, String stripeRefundId) {
        this.status = PaymentStatus.PARTIALLY_REFUNDED;
        this.refundAmount = (this.refundAmount != null ? this.refundAmount : 0) + refundAmount;
        this.stripeRefundId = stripeRefundId;
    }

    /**
     * キャンセルに遷移する。
     */
    public void markAsCancelled() {
        this.status = PaymentStatus.CANCELLED;
    }

    /**
     * Webhook 到達日時を記録する。
     */
    public void recordWebhookReceived() {
        this.webhookReceivedAt = LocalDateTime.now();
    }

    /**
     * 既存の返金額を差し引いた残額を算出する。
     *
     * @return 返金可能残額
     */
    public int getRefundableAmount() {
        return this.amount - (this.refundAmount != null ? this.refundAmount : 0);
    }
}
