package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * V196 {@code billing_return_state_nonces}（return state の一回消費 nonce）の JPA mapping。
 *
 * <p>保存するのは nonce の <b>ハッシュ</b> だけであり、token 平文・復帰 URL・メール等の PII は持たない。</p>
 */
@Entity
@Table(name = "billing_return_state_nonces")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingReturnStateNonceEntity extends UuidV7Entity {

    @Column(name = "nonce_hash", nullable = false, length = 64)
    private String nonceHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 24)
    private BillingReturnStateService.Purpose purpose;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "stripe_session_ref", length = 255)
    private String stripeSessionRef;

    @Column(name = "billing_customer_id", columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
