package com.mannschaft.app.billing.tax;

import lombok.Builder;
import lombok.Getter;

/**
 * 税導出結果（決定8）。band snapshot に保存する6項目
 * （amountExcludingTax/taxAmount/taxRateBasisPoints/taxNameSnapshot/amountIncludingTax/taxCodeSnapshot）
 * および taxMasterSnapshot（監査用の JSON snapshot）・includedInPrice を保持する。
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定8・AC-40。</p>
 */
@Getter
@Builder
public class BillingTaxDerivationResult {

    private final long amountExcludingTax;
    private final long taxAmount;
    private final long amountIncludingTax;
    private final int taxRateBasisPoints;
    private final String taxCodeSnapshot;
    private final String taxNameSnapshot;
    private final String taxMasterSnapshot;
    private final boolean includedInPrice;
}
