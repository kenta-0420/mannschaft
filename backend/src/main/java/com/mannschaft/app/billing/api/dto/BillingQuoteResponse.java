package com.mannschaft.app.billing.api.dto;

import java.time.Instant;
import java.util.UUID;

/** PR4 quote API の出力契約。 */
public record BillingQuoteResponse(UUID quoteId, long initialTotal, long nextMonthlyTotal,
                                   Instant expiresAt, Instant periodStart, Instant periodEnd) {
}
