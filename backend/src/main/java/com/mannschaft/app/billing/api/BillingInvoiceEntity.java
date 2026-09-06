package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * V196 {@code billing_invoices}（Stripe invoice 不変投影）の JPA mapping。
 *
 * <p>DDL: {@code V196.20260831142049__expand_billing_center.sql} 492行目付近。
 * scope_kind / status は DB 側の CHECK 制約と一致させる（{@link EntitlementScopeKind}、
 * DRAFT/OPEN/PAID/UNCOLLECTIBLE/VOID）。</p>
 */
@Entity
@Table(
        name = "billing_invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bi_psp", columnNames = "psp_invoice_ref")
        })
@Check(name = "chk_bi_scope", constraints = "scope_kind IN ('USER','TEAM','ORG')")
@Check(name = "chk_bi_currency", constraints = "currency = 'JPY'")
@Check(name = "chk_bi_status", constraints = "status IN ('DRAFT','OPEN','PAID','UNCOLLECTIBLE','VOID')")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingInvoiceEntity extends UuidV7Entity {

    @Column(name = "billing_customer_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    @Column(name = "contract_id", columnDefinition = "BINARY(16)")
    private UUID contractId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "psp_invoice_ref", nullable = false, length = 255)
    private String pspInvoiceRef;

    @Column(name = "psp_subscription_ref", length = 255)
    private String pspSubscriptionRef;

    /** DRAFT/OPEN/PAID/UNCOLLECTIBLE/VOID など Stripe billing_reason（例: subscription_cycle）。 */
    @Column(name = "billing_reason", nullable = false, length = 32)
    private String billingReason;

    /** DRAFT/OPEN/PAID/UNCOLLECTIBLE/VOID。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal_amount", nullable = false)
    private Long subtotalAmount;

    @Column(name = "discount_amount", nullable = false)
    private Long discountAmount;

    @Column(name = "tax_amount", nullable = false)
    private Long taxAmount;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "issuer_name_snapshot", nullable = false, length = 255)
    private String issuerNameSnapshot;

    @Column(name = "billing_name_snapshot", length = 255)
    private String billingNameSnapshot;

    @Column(name = "billing_email_snapshot", length = 254)
    private String billingEmailSnapshot;

    /** 請求先住所 snapshot（JSON）。 */
    @Column(name = "billing_address_snapshot", columnDefinition = "JSON")
    private String billingAddressSnapshot;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
