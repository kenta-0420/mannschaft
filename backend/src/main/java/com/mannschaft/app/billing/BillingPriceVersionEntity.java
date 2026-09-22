package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "billing_price_versions",
        // V196 の uk_bpv_identity / uk_bpv_revision_no / uk_bpv_catalog_revision と同一。
        // test profile は ddl-auto=create で Entity から schema を作るため、ここに書かないと
        // テストの schema にだけ UNIQUE が無い状態になる（uk_bcc_invoice と同型の宣言漏れ）。
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bpv_identity",
                        columnNames = {"id", "product_kind", "product_key", "scope_kind"}),
                @UniqueConstraint(name = "uk_bpv_revision_no",
                        columnNames = {"product_kind", "product_key", "scope_kind", "revision_no"}),
                @UniqueConstraint(name = "uk_bpv_catalog_revision",
                        columnNames = {"product_kind", "product_key", "scope_kind", "catalog_revision"}),
                @UniqueConstraint(name = "uk_bpv_single_future",
                        columnNames = {"future_reservation_key"})
        })
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

    /**
     * 単一 future 予約制限（マスター裁可・第6版）を DB 側で強制するための生成列。
     * {@code status} が DRAFT/READY/SCHEDULED のときのみ非 null になり、
     * {@code uk_bpv_single_future} が同一 (product_kind, product_key, scope_kind) の
     * 同時 future を1本に制限する。アプリからは読み取り専用。
     */
    @Column(name = "future_reservation_key", insertable = false, updatable = false,
            columnDefinition = "VARCHAR(200) GENERATED ALWAYS AS "
                    + "(CASE WHEN status IN ('DRAFT','READY','SCHEDULED') "
                    + "THEN CONCAT(product_kind, '|', product_key, '|', scope_kind) ELSE NULL END) STORED")
    private String futureReservationKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = BillingPriceVersionStatus.DRAFT;
        }
        if (provisionAttempts == null) {
            provisionAttempts = 0;
        }
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
