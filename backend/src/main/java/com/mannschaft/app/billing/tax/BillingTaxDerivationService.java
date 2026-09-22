package com.mannschaft.app.billing.tax;

import com.mannschaft.app.billing.BillingTaxBehavior;
import org.springframework.stereotype.Service;

/**
 * 税導出サービス（決定8）。band の {@code effectiveFrom} 時点で解決した
 * {@link BillingTaxCodeEntity} を用い、band create のトランザクション内で一度だけ導出する
 * （Provision 時の再導出はしない）。
 *
 * <p>端数は常に切り捨て（floor）とし、{@code amountExcludingTax + taxAmount == amountIncludingTax}
 * が EXCLUSIVE/INCLUSIVE いずれの入力でも成立するよう、常に excl と tax から incl を導出する。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定8・AC-27・AC-28・AC-39〜AC-41。</p>
 */
@Service
public class BillingTaxDerivationService {

    /** AC-28: inputAmount の上限（この値を超えると400相当）。 */
    public static final long MAX_INPUT_AMOUNT = 9_999_999L;

    public BillingTaxDerivationResult derive(long inputAmount, BillingTaxBehavior taxBehavior, BillingTaxCodeEntity taxCode) {
        if (inputAmount <= 0) {
            throw new IllegalArgumentException("inputAmount は正の値である必要があります: " + inputAmount);
        }
        if (inputAmount > MAX_INPUT_AMOUNT) {
            throw new IllegalArgumentException("inputAmount は上限 " + MAX_INPUT_AMOUNT + " を超えられません: " + inputAmount);
        }

        int rateBasisPoints = taxCode.getRateBasisPoints();
        long amountExcludingTax;
        long taxAmount;
        long amountIncludingTax;

        if (taxBehavior == BillingTaxBehavior.INCLUSIVE) {
            amountIncludingTax = inputAmount;
            amountExcludingTax = (inputAmount * 10000L) / (10000L + rateBasisPoints);
            taxAmount = amountIncludingTax - amountExcludingTax;
        } else {
            amountExcludingTax = inputAmount;
            taxAmount = (amountExcludingTax * rateBasisPoints) / 10000L;
            amountIncludingTax = amountExcludingTax + taxAmount;
        }

        String taxMasterSnapshot = String.format(
                "{\"code\":\"%s\",\"displayName\":\"%s\",\"rateBasisPoints\":%d}",
                taxCode.getCode(), taxCode.getDisplayName(), rateBasisPoints);

        return BillingTaxDerivationResult.builder()
                .amountExcludingTax(amountExcludingTax)
                .taxAmount(taxAmount)
                .amountIncludingTax(amountIncludingTax)
                .taxRateBasisPoints(rateBasisPoints)
                .taxCodeSnapshot(taxCode.getCode())
                .taxNameSnapshot(taxCode.getDisplayName())
                .taxMasterSnapshot(taxMasterSnapshot)
                .includedInPrice(taxBehavior == BillingTaxBehavior.INCLUSIVE)
                .build();
    }
}
