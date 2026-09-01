package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * F20.1 の不変な価格カタログ revision。
 *
 * <p>金額・税額・Stripe Price は子 {@link BillingPriceBandVersionEntity} のみが持つ。
 * {@code lockVersion} は Provision / activate 時の CAS 専用であり、revision 番号とは別物である。</p>
 */
@Entity
@Table(name = "billing_price_versions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingPriceVersionEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "product_kind", nullable = false, length = 8)
    private BillingProductKind productKind;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "catalog_revision", nullable = false, length = 64)
    private String catalogRevision;

    @Column(name = "revision_no", nullable = false)
    private Long revisionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingPriceVersionStatus status;

    @Column(name = "provision_attempts", nullable = false)
    private Integer provisionAttempts;

    @Column(name = "last_provision_error_code", length = 64)
    private String lastProvisionErrorCode;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_by")
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "creation_source", nullable = false, length = 24)
    private BillingPriceCreationSource creationSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = BillingPriceVersionStatus.DRAFT;
        }
        if (provisionAttempts == null) {
            provisionAttempts = 0;
        }
    }
}
