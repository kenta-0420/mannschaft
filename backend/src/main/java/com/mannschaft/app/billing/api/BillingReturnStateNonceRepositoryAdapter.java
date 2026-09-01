package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** {@link BillingReturnStateNonceRepository} の JPA 実装。 */
@Component
@RequiredArgsConstructor
class BillingReturnStateNonceRepositoryAdapter implements BillingReturnStateNonceRepository {

    private final BillingReturnStateNonceJpaRepository nonceJpaRepository;

    @Override
    public void register(String nonceHash, BillingReturnStateService.Purpose purpose, long actorId,
                         EntitlementScopeKind scopeKind, long scopeId, Instant expiresAt) {
        nonceJpaRepository.saveAndFlush(BillingReturnStateNonceEntity.builder()
                .nonceHash(nonceHash)
                .purpose(purpose)
                .actorId(actorId)
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(scopeKind == EntitlementScopeKind.ORG ? scopeId : null)
                .expiresAt(expiresAt)
                .build());
    }

    @Override
    public int consumeIfValid(String nonceHash, BillingReturnStateService.Purpose purpose, long actorId,
                              EntitlementScopeKind scopeKind, long scopeId, Instant now) {
        if (nonceHash == null || purpose == null || scopeKind == null || now == null) {
            return 0;
        }
        return nonceJpaRepository.consumeIfValid(nonceHash, purpose, actorId, scopeKind, scopeId, now);
    }
}
