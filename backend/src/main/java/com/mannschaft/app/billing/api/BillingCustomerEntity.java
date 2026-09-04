package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * V196 {@code billing_customers}（scope 所有 Stripe Customer）の JPA mapping。
 *
 * <p>Checkout からは「scope_kind + scope_id + id の三点一致かつ status=ACTIVE」でのみ引く
 * （他 scope の Customer を掴めないようにする IDOR 防止）。billing_email / billing_name は
 * PII のため本 PR の読み取り経路では参照しない。</p>
 */
@Entity
@Table(name = "billing_customers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingCustomerEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "organization_id")
    private Long organizationId;

    /** Stripe Customer ID（{@code cus_xxx}）。status=ACTIVE のときのみ非 NULL。 */
    @Column(name = "psp_customer_ref", length = 255)
    private String pspCustomerRef;

    @Column(name = "billing_email", length = 254)
    private String billingEmail;

    @Column(name = "billing_name", length = 255)
    private String billingName;

    /** PROVISIONING / ACTIVE / PROVISION_FAILED / MIGRATION_REQUIRED / CLOSED。 */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "provision_attempts", nullable = false)
    private Integer provisionAttempts;

    @Column(name = "last_provision_error_code", length = 64)
    private String lastProvisionErrorCode;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
