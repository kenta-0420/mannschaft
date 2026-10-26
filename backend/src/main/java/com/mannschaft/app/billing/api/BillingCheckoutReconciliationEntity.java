package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * V198 {@code billing_checkout_reconciliations}（Checkout 照合キュー）の JPA mapping。
 *
 * <p>「Stripe 側に Checkout Session が実在するのに DB 側が倒れた」事実を耐久化する。持つのは Stripe の
 * 不透明 ID（{@code cs_...} / {@code cus_...}）と退避の識別子だけであり、Checkout URL・return state token・
 * client secret・raw payload・PII は保存しない。</p>
 */
@Entity
@Table(name = "billing_checkout_reconciliations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bcr_session", columnNames = "stripe_session_ref"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingCheckoutReconciliationEntity extends UuidV7Entity {

    /** Stripe Checkout Session ID。{@code uk_bcr_session} で同一 Session の重複退避を防ぐ。 */
    @Column(name = "stripe_session_ref", nullable = false, length = 255)
    private String stripeSessionRef;

    /** Stripe Customer ID。回収時に Session の所有 Customer を突き合わせるために持つ。 */
    @Column(name = "stripe_customer_ref", nullable = false, length = 255)
    private String stripeCustomerRef;

    /** 退避の識別子（呼び出し元が採番）。運用ログと DB 行を突き合わせる鍵。 */
    @Column(name = "idempotency_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID idempotencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BillingCheckoutReconciliationStatus status;

    /** 同一 Session が繰り返し退避された回数。 */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        if (status == null) {
            status = BillingCheckoutReconciliationStatus.PENDING;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
    }
}
