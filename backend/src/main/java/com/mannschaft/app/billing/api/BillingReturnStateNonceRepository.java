package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;

import java.time.Instant;

/** V196 billing_return_state_nonces の hash 保存・一回消費 CAS 境界。 */
interface BillingReturnStateNonceRepository {
    void register(String nonceHash, BillingReturnStateService.Purpose purpose, long actorId,
                  EntitlementScopeKind scopeKind, long scopeId, Instant expiresAt);

    int consumeIfValid(String nonceHash, BillingReturnStateService.Purpose purpose, long actorId,
                       EntitlementScopeKind scopeKind, long scopeId, Instant now);
}
