package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 返金記録（部分/全額）。
 *
 * <p>{@code escrow_transaction_id} は payment ドメイン内 FK（CASCADE）。
 * {@code refunded_by_user_id} は users への論理参照（FKなし・監査）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.4</p>
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RefundEntity extends UuidV7Entity {

    @Column(name = "escrow_transaction_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID escrowTransactionId;

    @Column(name = "stripe_refund_id", nullable = false, length = 32)
    private String stripeRefundId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Column(name = "refunded_by_user_id")
    private Long refundedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private RefundStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.currency == null) {
            this.currency = "JPY";
        }
        if (this.status == null) {
            this.status = RefundStatus.PENDING;
        }
    }
}
