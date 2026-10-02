package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.EntitlementScopeKind;

/** PR4 quote API の入力契約。実装は次工程で追加する。 */
public record CreateBillingQuoteRequest(EntitlementScopeKind scopeKind, Long scopeId,
                                        String productKind, String productKey) {
}
