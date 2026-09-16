package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BC-13: V196 billing_quotes の一回消費 CAS を JPA slice で固定する。 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_quotes CAS DataJpaTest")
class BillingQuoteRepositoryDataJpaTest {
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");
    private static final long ACTOR_ID = 7L;

    @Autowired private BillingQuoteJpaRepository repository;

    @Test
    @DisplayName("BC-13: 消費は actor と version が一致し未消費・未失効のときだけ一度成功する")
    void consume_束縛一致時だけ一度成功する() {
        BillingQuoteEntity quote = repository.saveAndFlush(quote(NOW.plusSeconds(600)));

        int otherActor = repository.consumeIfUnchanged(quote.getId(), 8L, 0L, NOW);
        int staleVersion = repository.consumeIfUnchanged(quote.getId(), ACTOR_ID, 1L, NOW);
        int consumed = repository.consumeIfUnchanged(quote.getId(), ACTOR_ID, 0L, NOW);
        int replay = repository.consumeIfUnchanged(quote.getId(), ACTOR_ID, 0L, NOW);

        assertThat(otherActor).isZero();
        assertThat(staleVersion).isZero();
        assertThat(consumed).isEqualTo(1);
        assertThat(replay).isZero();
        BillingQuoteEntity stored = repository.findById(quote.getId()).orElseThrow();
        assertThat(stored.getConsumedAt()).isEqualTo(NOW);
        assertThat(stored.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("BC-13: 失効済み quote は消費できない")
    void consume_失効済みは弾く() {
        BillingQuoteEntity expired = repository.saveAndFlush(quote(NOW.minusSeconds(1)));

        assertThat(repository.consumeIfUnchanged(expired.getId(), ACTOR_ID, 0L, NOW)).isZero();
        assertThat(repository.findById(expired.getId()).orElseThrow().getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("BC-13: 論理削除済み quote は消費も取得もできない")
    void consume_論理削除済みは弾く() {
        BillingQuoteEntity deleted = quote(NOW.plusSeconds(600));
        deleted.setDeletedAt(NOW.minusSeconds(60));
        repository.saveAndFlush(deleted);

        assertThat(repository.consumeIfUnchanged(deleted.getId(), ACTOR_ID, 0L, NOW)).isZero();
        assertThat(repository.findByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
    }

    private BillingQuoteEntity quote(Instant expiresAt) {
        return BillingQuoteEntity.builder()
                .actorId(ACTOR_ID)
                .billingCustomerId(UUID.fromString("01999d74-5130-7000-8000-000000000001"))
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(91L)
                .productKind(BillingProductKind.PLAN)
                .productKey("PRO")
                .priceBandVersionId(UUID.fromString("01999d74-5130-7000-8000-000000000002"))
                .memberCount(21)
                .taxSnapshot("{\"rateBasisPoints\":1000}")
                .amountSnapshot("{\"initialTotal\":null,\"nextMonthlyTotal\":null}")
                .periodStart(Instant.parse("2028-02-01T00:00:00Z"))
                .periodEnd(Instant.parse("2028-03-01T00:00:00Z"))
                .prorationAt(NOW)
                .requestHash("a".repeat(64))
                .expiresAt(expiresAt)
                .version(0L)
                .createdAt(NOW.minusSeconds(10))
                .build();
    }
}
