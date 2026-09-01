package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** PR4署名return stateの公開契約をコンパイル可能にする未実装骨格。 */
@RequiredArgsConstructor
public class BillingReturnStateService {
    public enum Purpose { CHECKOUT_SUCCESS, CHECKOUT_CANCEL, PORTAL_RETURN, PAYMENT_ACTION_RETURN }

    public record ReturnState(Purpose purpose, EntitlementScopeKind scopeKind, long scopeId,
                              long actorId, String tab, UUID quoteId, String sessionId,
                              UUID billingCustomerId, Instant issuedAt, Instant expiresAt,
                              String nonce) { }

    private final Clock clock;
    private final BillingReturnSigningKeyProvider signingKeyProvider;
    private final BillingReturnStateNonceRepository nonceRepository;

    public String issue(ReturnState state) { throw new IllegalStateException("HMAC state is not implemented"); }

    public ReturnState verify(String signedState, Purpose expectedPurpose) {
        throw new UnsupportedOperationException("PR4 return state verification is not implemented");
    }

    public void consumeNonce(ReturnState state, long actorId) {
        throw new UnsupportedOperationException("PR4 nonce consumption is not implemented");
    }
}
