package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BC-23: V196 PROCESSING lease の所有者付きCASをJPA sliceで固定する。 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_api_idempotencies CAS DataJpaTest")
class BillingApiIdempotencyRepositoryDataJpaTest {
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");

    @Autowired private BillingApiIdempotencyJpaRepository repository;

    @Test
    @DisplayName("BC-23: completeはPROCESSINGかつlease owner一致時だけ一度成功する")
    void complete_leaseOwner一致時だけ一度成功する() {
        BillingApiIdempotencyEntity entity = repository.saveAndFlush(processing("owner-a", NOW.plusSeconds(60)));

        int wrongOwner = repository.completeIfLeaseOwner(entity.getId(), "owner-b", 201, "{}", NOW);
        int correctOwner = repository.completeIfLeaseOwner(entity.getId(), "owner-a", 201, "{}", NOW);
        int replay = repository.completeIfLeaseOwner(entity.getId(), "owner-a", 201, "{}", NOW);

        assertThat(wrongOwner).isZero();
        assertThat(correctOwner).isEqualTo(1);
        assertThat(replay).isZero();
        BillingApiIdempotencyEntity stored = repository.findById(entity.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BillingIdempotencyStatus.SUCCEEDED);
        assertThat(stored.getResponseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("BC-23: stale recoveryは観測owner/expiry一致かつ期限切れ時だけCAS成功する")
    void recoverStale_観測値一致時だけ一度成功する() {
        Instant expiredAt = NOW.minusSeconds(1);
        BillingApiIdempotencyEntity entity = repository.saveAndFlush(processing("owner-a", expiredAt));

        int notExpiredObservation = repository.recoverStaleLease(entity.getId(), "owner-a",
                NOW.plusSeconds(1), "worker-b", NOW.plusSeconds(120), NOW);
        int acquired = repository.recoverStaleLease(entity.getId(), "owner-a",
                expiredAt, "worker-b", NOW.plusSeconds(120), NOW);
        int duplicate = repository.recoverStaleLease(entity.getId(), "owner-a",
                expiredAt, "worker-c", NOW.plusSeconds(120), NOW);

        assertThat(notExpiredObservation).isZero();
        assertThat(acquired).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(repository.findById(entity.getId()).orElseThrow().getLeaseOwner()).isEqualTo("worker-b");
    }

    @Test
    @DisplayName("P2-1: 同一 actor/method/path/key の二重予約は uk_bai_actor_request で必ず弾かれる")
    void reserve_同一キーの二重予約はUNIQUE違反になる() {
        repository.saveAndFlush(processing("owner-a", NOW.plusSeconds(60)));

        // service 側が「競合を冪等応答へ写す」ために捕捉する例外型を、実 DDL 上で固定する。
        assertThatThrownBy(() -> repository.saveAndFlush(processing("owner-b", NOW.plusSeconds(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private BillingApiIdempotencyEntity processing(String owner, Instant leaseExpiry) {
        return BillingApiIdempotencyEntity.builder()
                .actorId(7L)
                .httpMethod("POST")
                .requestPath("/api/v1/me/billing/checkout-sessions")
                .idempotencyKey("00000000-0000-0000-0000-000000000301")
                .requestHash("c".repeat(64))
                .status(BillingIdempotencyStatus.PROCESSING)
                .leaseOwner(owner)
                .leaseExpiresAt(leaseExpiry)
                .startedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(86400))
                .createdAt(NOW.minusSeconds(10))
                .build();
    }
}
