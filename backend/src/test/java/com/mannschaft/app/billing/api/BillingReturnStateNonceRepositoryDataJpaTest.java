package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** BC-16/28: V196 billing_return_state_nonces の一回消費 CAS を JPA slice で固定する。 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_return_state_nonces CAS DataJpaTest")
class BillingReturnStateNonceRepositoryDataJpaTest {
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");
    private static final String NONCE_HASH = "b".repeat(64);
    private static final long ACTOR_ID = 7L;
    private static final long SCOPE_ID = 91L;

    @Autowired private BillingReturnStateNonceJpaRepository repository;

    @Test
    @DisplayName("BC-28: purpose/actor/scope/hash がすべて一致するときだけ一度だけ消費できる")
    void consume_束縛一致時だけ一度成功する() {
        repository.saveAndFlush(nonce(NONCE_HASH, NOW.plusSeconds(600)));

        int otherPurpose = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_CANCEL, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);
        int otherActor = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, 8L,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);
        int otherScopeKind = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.ORG, SCOPE_ID, NOW);
        int otherScopeId = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, 92L, NOW);
        int unknownHash = repository.consumeIfValid("c".repeat(64),
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);
        int consumed = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);
        int replay = repository.consumeIfValid(NONCE_HASH,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);

        assertThat(otherPurpose).isZero();
        assertThat(otherActor).isZero();
        assertThat(otherScopeKind).isZero();
        assertThat(otherScopeId).isZero();
        assertThat(unknownHash).isZero();
        assertThat(consumed).isEqualTo(1);
        assertThat(replay).isZero();
    }

    @Test
    @DisplayName("BC-28: 失効済み nonce は消費できない")
    void consume_失効済みは弾く() {
        String expiredHash = "d".repeat(64);
        repository.saveAndFlush(nonce(expiredHash, NOW.minusSeconds(1)));

        assertThat(repository.consumeIfValid(expiredHash,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW)).isZero();
    }

    private BillingReturnStateNonceEntity nonce(String nonceHash, Instant expiresAt) {
        return BillingReturnStateNonceEntity.builder()
                .nonceHash(nonceHash)
                .purpose(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS)
                .actorId(ACTOR_ID)
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(SCOPE_ID)
                .expiresAt(expiresAt)
                .createdAt(NOW.minusSeconds(10))
                .build();
    }
}
