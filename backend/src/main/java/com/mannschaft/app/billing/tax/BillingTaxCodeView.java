package com.mannschaft.app.billing.tax;

import java.time.Instant;
import java.util.UUID;

/**
 * 税コードマスタ（{@link BillingTaxCodeEntity}）を他ドメイン・Controller 層へ公開するための
 * 読み取り専用ビュー（D-1 API 境界: {@code @Service} の到達可能な public メソッドは
 * Entity を引数・戻り値に持てないため、{@link BillingTaxCodeService}・
 * {@link BillingTaxDerivationService} の公開シグネチャはすべてこの DTO を経由する）。
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-4〜AC-16。</p>
 */
public record BillingTaxCodeView(
        UUID id,
        String code,
        String displayName,
        int rateBasisPoints,
        String stripeTaxCode,
        Instant validFrom,
        Instant validUntil,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static BillingTaxCodeView from(BillingTaxCodeEntity entity) {
        return new BillingTaxCodeView(
                entity.getId(),
                entity.getCode(),
                entity.getDisplayName(),
                entity.getRateBasisPoints(),
                entity.getStripeTaxCode(),
                entity.getValidFrom(),
                entity.getValidUntil(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
