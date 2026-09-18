package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.PaymentRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_request_payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class PaymentRequestPaymentAttemptEntity extends UuidV7Entity {
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "payment_request_id", nullable = false)
    private UUID paymentRequestId;

    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    @Column(name = "client_key_hash", nullable = false, length = 64)
    private String clientKeyHash;

    @Column(name = "stripe_idempotency_key", nullable = false, length = 64)
    private String stripeIdempotencyKey;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "escrow_transaction_id")
    private UUID escrowTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 12)
    private PaymentRequestStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private PaymentRequestPaymentAttemptStatus status = PaymentRequestPaymentAttemptStatus.CREATING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public void attachStripe(String paymentIntentId, UUID escrowId) {
        this.stripePaymentIntentId = paymentIntentId;
        this.escrowTransactionId = escrowId;
        this.status = PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION;
        this.updatedAt = LocalDateTime.now();
    }

    public void succeed() {
        this.status = PaymentRequestPaymentAttemptStatus.SUCCEEDED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }

    public void noteRetryableFailure(String code) {
        this.failureCode = code;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String code) {
        this.status = PaymentRequestPaymentAttemptStatus.FAILED;
        this.failureCode = code;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }
}
