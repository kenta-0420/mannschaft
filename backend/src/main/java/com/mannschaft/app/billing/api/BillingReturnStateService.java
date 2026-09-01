package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import java.time.Instant;
import java.util.UUID;

/** PR4署名return stateの未実装骨格。 */
public class BillingReturnStateService {
    public enum Purpose { CHECKOUT_SUCCESS, CHECKOUT_CANCEL, PORTAL_RETURN, PAYMENT_ACTION_RETURN }
    public record ReturnState(Purpose purpose, EntitlementScopeKind scopeKind, long scopeId,
                              long actorId, UUID quoteId, String sessionId, Instant expiresAt) { }
    public String issue(ReturnState state) { throw new IllegalStateException("HMAC state is not implemented"); }
    public ReturnState consume(String signedState, long actorId) { throw new IllegalStateException("nonce consume is not implemented"); }
}
