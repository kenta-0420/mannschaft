package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * V196 {@code billing_invoice_adjustments}（返金・credit note・dispute の不変複数行投影）の JPA mapping。
 *
 * <p>DDL: {@code V196.20260831142049__expand_billing_center.sql} 534行目付近。
 * kind / status は DB 側の CHECK 制約と一致させる。</p>
 */
@Entity
@Table(
        name = "billing_invoice_adjustments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bia_object", columnNames = "psp_object_ref")
        })
@Check(name = "chk_bia_kind", constraints = "kind IN ('REFUND','CREDIT_NOTE','DISPUTE')")
@Check(name = "chk_bia_currency", constraints = "currency = 'JPY'")
@Check(name = "chk_bia_amount", constraints = "amount >= 0")
@Check(name = "chk_bia_status",
        constraints = "status IN ('PENDING','SUCCEEDED','FAILED','OPEN','WON','LOST','CLOSED')")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingInvoiceAdjustmentEntity extends UuidV7Entity {

    @Column(name = "invoice_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID invoiceId;

    @Column(name = "operation_id", columnDefinition = "BINARY(16)")
    private UUID operationId;

    @Column(name = "organization_id")
    private Long organizationId;

    /** REFUND / CREDIT_NOTE / DISPUTE。 */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "psp_object_ref", nullable = false, length = 255)
    private String pspObjectRef;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** PENDING/SUCCEEDED/FAILED/OPEN/WON/LOST/CLOSED。 */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
