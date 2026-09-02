package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** {@link BillingReturnStateNonceRepository} の JPA 実装。 */
@Component
@RequiredArgsConstructor
class BillingReturnStateNonceRepositoryAdapter implements BillingReturnStateNonceRepository {

    private final BillingReturnStateNonceJpaRepository nonceJpaRepository;

    @Override
    @Transactional
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

    /** nonce の一回消費 CAS（{@code @Modifying}）。callback は非トランザクションで到達するため境界を張る。 */
    @Override
    @Transactional
    public int consumeIfValid(String nonceHash, BillingReturnStateService.Purpose purpose, long actorId,
                              EntitlementScopeKind scopeKind, long scopeId, Instant now) {
        if (nonceHash == null || purpose == null || scopeKind == null || now == null) {
            return 0;
        }
        return nonceJpaRepository.consumeIfValid(nonceHash, purpose, actorId, scopeKind, scopeId, now);
    }
}
