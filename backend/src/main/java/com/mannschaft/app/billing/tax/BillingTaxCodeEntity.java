package com.mannschaft.app.billing.tax;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * 価格改定戦役（price-revisions）決定6: 税コードマスタ（{@code billing_tax_codes}）。
 *
 * <p>同一 {@code code} で複数の有効期間（税率改定履歴）を持てる。有効期間の重なり判定は
 * {@link BillingTaxCodeService} が専用ロック行 {@code __TAX_CODE_LOCK__} を {@code FOR UPDATE}
 * してから直列に行う（決定6改訂・AC-5/AC-11）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-1〜AC-16。</p>
 */
@Entity
@Table(name = "billing_tax_codes",
        uniqueConstraints = @UniqueConstraint(name = "uk_btc_code_from", columnNames = {"code", "valid_from"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingTaxCodeEntity extends UuidV7Entity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "rate_basis_points", nullable = false)
    private int rateBasisPoints;

    @Column(name = "stripe_tax_code", length = 64)
    private String stripeTaxCode;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
