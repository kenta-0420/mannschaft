package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingProductKind;

import java.time.Instant;
import java.util.UUID;

/** PR4 quote API の出力契約。 */
public record BillingQuoteResponse(
        UUID quoteId,
        BillingProductKind productKind,
        String productKey,
        Money initialTotal,
        Money nextMonthlyTotal,
        Instant expiresAt,
        Instant periodStart,
        Instant periodEnd) {

    public record Money(String currency, long amountIncludingTax, long amountExcludingTax,
                        long taxAmount, String taxName, Integer taxRateBasisPoints) { }
}
