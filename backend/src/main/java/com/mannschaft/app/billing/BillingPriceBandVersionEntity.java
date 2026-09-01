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
import java.util.UUID;

/**
 * F20.1 の販売可能な価格 band snapshot。
 *
 * <p>税込・税抜・税額および Stripe Price を同じ snapshot に保持する唯一の販売正本である。
 * 親 revision への複合 FK は、JPA 関連ではなく {@code priceVersionId} と商品識別子を明示して保持する。</p>
 */
@Entity
@Table(name = "billing_price_band_versions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingPriceBandVersionEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "product_kind", nullable = false, length = 8)
    private BillingProductKind productKind;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "band_no", nullable = false)
    private Integer bandNo;

    @Column(name = "min_members", nullable = false)
    private Integer minMembers;

    @Column(name = "max_members")
    private Integer maxMembers;

    @Column(name = "price_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID priceVersionId;

    @Column(name = "stripe_price_ref", length = 255)
    private String stripePriceRef;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "input_amount", nullable = false)
    private Long inputAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_behavior", nullable = false, length = 16)
    private BillingTaxBehavior taxBehavior;

    @Column(name = "tax_code_snapshot", nullable = false, length = 64)
    private String taxCodeSnapshot;

    @Column(name = "tax_master_snapshot", nullable = false, columnDefinition = "JSON")
    private String taxMasterSnapshot;

    @Column(name = "amount_excluding_tax", nullable = false)
    private Long amountExcludingTax;

    @Column(name = "tax_amount", nullable = false)
    private Long taxAmount;

    @Column(name = "tax_rate_basis_points", nullable = false)
    private Integer taxRateBasisPoints;

    @Column(name = "tax_name_snapshot", nullable = false, length = 64)
    private String taxNameSnapshot;

    @Column(name = "is_included_in_price", nullable = false)
    private boolean includedInPrice;

    @Column(name = "amount_including_tax", nullable = false)
    private Long amountIncludingTax;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingPriceVersionStatus status;

    @Column(name = "provision_error_code", length = 64)
    private String provisionErrorCode;

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
        if (currency == null) {
            currency = "JPY";
        }
        if (status == null) {
            status = BillingPriceVersionStatus.DRAFT;
        }
    }
}
