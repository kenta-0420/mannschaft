package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BC-23: V198 {@code billing_checkout_reconciliations}（Checkout 照合キュー）を JPA slice で固定する。
 *
 * <p>同一 Session が重複退避されないこと（{@code uk_bcr_session} の「弾く」側）と、未回収件数が
 * SQL 一発で数えられることを観測する。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_checkout_reconciliations DataJpaTest")
class BillingCheckoutReconciliationRepositoryDataJpaTest {
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");
    private static final String SESSION = "cs_test_1";
    private static final String CUSTOMER = "cus_scope_team_91";

    @Autowired private BillingCheckoutReconciliationJpaRepository repository;

    @Test
    @DisplayName("BC-23: 退避行は PENDING で残り、未回収件数として数えられる")
    void enqueue_退避行はPENDINGで数えられる() {
        repository.saveAndFlush(entry());

        assertThat(repository.countByStatus(BillingCheckoutReconciliationStatus.PENDING)).isEqualTo(1);
        BillingCheckoutReconciliationEntity stored = repository.findByStripeSessionRef(SESSION).orElseThrow();
        assertThat(stored.getStripeCustomerRef()).isEqualTo(CUSTOMER);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo(BillingCheckoutReconciliationStatus.PENDING);
    }

    @Test
    @DisplayName("BC-23: 同一 session の重複退避は uk_bcr_session が弾く")
    void enqueue_同一sessionの重複行は弾く() {
        repository.saveAndFlush(entry());

        BillingCheckoutReconciliationEntity duplicate = entry();
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BC-23: 再 enqueue は行を増やさず attempt_count を積み、RESOLVED を PENDING へ戻す")
    void recordRetry_行を増やさず積み増す() {
        BillingCheckoutReconciliationEntity resolved = entry();
        resolved.setStatus(BillingCheckoutReconciliationStatus.RESOLVED);
        repository.saveAndFlush(resolved);
        assertThat(repository.countByStatus(BillingCheckoutReconciliationStatus.PENDING)).isZero();

        int retried = repository.recordRetry(SESSION, NOW.plusSeconds(60));

        assertThat(retried).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        BillingCheckoutReconciliationEntity stored = repository.findByStripeSessionRef(SESSION).orElseThrow();
        assertThat(stored.getAttemptCount()).isEqualTo(2);
        assertThat(stored.getStatus()).isEqualTo(BillingCheckoutReconciliationStatus.PENDING);
        assertThat(stored.getLastErrorAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.countByStatus(BillingCheckoutReconciliationStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @DisplayName("BC-23: 未退避 session への積み増しは 0 行（新規 INSERT 経路へ倒すため）")
    void recordRetry_未退避sessionは0行() {
        assertThat(repository.recordRetry("cs_unknown", NOW)).isZero();
        assertThat(repository.count()).isZero();
    }

    private BillingCheckoutReconciliationEntity entry() {
        return BillingCheckoutReconciliationEntity.builder()
                .stripeSessionRef(SESSION)
                .stripeCustomerRef(CUSTOMER)
                .idempotencyId(UUID.fromString("01999d74-5130-7000-8000-000000000001"))
                .status(BillingCheckoutReconciliationStatus.PENDING)
                .attemptCount(1)
                .lastErrorAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
