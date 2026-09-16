package com.mannschaft.app.billing.api.dto;

import java.util.UUID;

/** PR4 Checkout API の入力契約。 */
public record CreateBillingCheckoutSessionRequest(UUID quoteId) {
}
