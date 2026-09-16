package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingProductKind;
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
 * V196 {@code billing_quotes}（Checkout 直前に再照合する 10 分見積り）の JPA mapping。
 *
 * <p>{@code version} は {@code @Version}（Hibernate の暗黙 optimistic lock）ではなく素の列とし、
 * 消費は {@link BillingQuoteJpaRepository#consumeIfUnchanged} の条件付き UPDATE（CAS）だけで行う
 * （{@code billing_api_idempotencies} の lease CAS と同じ金型）。</p>
 */
@Entity
@Table(name = "billing_quotes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingQuoteEntity extends UuidV7Entity {

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "billing_customer_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_kind", nullable = false, length = 8)
    private BillingProductKind productKind;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Column(name = "price_band_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID priceBandVersionId;

    @Column(name = "member_count")
    private Integer memberCount;

    /** 価格版の税 master snapshot（JSON）。 */
    @Column(name = "tax_snapshot", nullable = false, columnDefinition = "JSON")
    private String taxSnapshot;

    /** 初回（日割り）と翌月満額の金額 snapshot（JSON）。 */
    @Column(name = "amount_snapshot", nullable = false, columnDefinition = "JSON")
    private String amountSnapshot;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "proration_at", nullable = false)
    private Instant prorationAt;

    @Column(name = "contract_version")
    private Long contractVersion;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (version == null) {
            version = 0L;
        }
    }
}
