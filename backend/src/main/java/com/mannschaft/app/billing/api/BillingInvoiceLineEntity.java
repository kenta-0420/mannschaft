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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * V196 {@code billing_invoice_lines}（請求明細不変投影）の JPA mapping。
 *
 * <p>DDL: {@code V196.20260831142049__expand_billing_center.sql} 566行目付近。
 * 本テーブルは {@code created_at} のみで {@code updated_at} / {@code deleted_at} を持たない
 * （不変投影のため、DDL の列定義通り）。</p>
 */
@Entity
@Table(
        name = "billing_invoice_lines",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bil_line", columnNames = {"invoice_id", "psp_line_ref"})
        })
@Check(name = "chk_bil_quantity", constraints = "quantity > 0")
@Check(name = "chk_bil_tax",
        constraints = "tax_rate_basis_points IS NULL OR tax_rate_basis_points BETWEEN 0 AND 10000")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingInvoiceLineEntity extends UuidV7Entity {

    @Column(name = "invoice_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID invoiceId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "price_band_version_id", columnDefinition = "BINARY(16)")
    private UUID priceBandVersionId;

    @Column(name = "stripe_price_ref", length = 255)
    private String stripePriceRef;

    @Column(name = "psp_line_ref", nullable = false, length = 255)
    private String pspLineRef;

    @Column(name = "description_snapshot", nullable = false, length = 500)
    private String descriptionSnapshot;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "amount_excluding_tax", nullable = false)
    private Long amountExcludingTax;

    @Column(name = "discount_amount", nullable = false)
    private Long discountAmount;

    @Column(name = "tax_name_snapshot", length = 64)
    private String taxNameSnapshot;

    @Column(name = "tax_rate_basis_points")
    private Integer taxRateBasisPoints;

    @Column(name = "tax_amount", nullable = false)
    private Long taxAmount;

    @Column(name = "is_included_in_price", nullable = false)
    private Boolean includedInPrice;

    @Column(name = "amount_including_tax", nullable = false)
    private Long amountIncludingTax;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
