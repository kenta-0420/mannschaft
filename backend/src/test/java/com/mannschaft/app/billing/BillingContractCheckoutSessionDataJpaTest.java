package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-13/23: V198 {@code billing_contracts.stripe_checkout_session_ref} の紐付け CAS を JPA slice で固定する。
 *
 * <p>合格側だけでなく「弾く側」（PENDING でない・別 session ref を既に持つ・論理削除済み・不在）を必ず観測する。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_contracts Checkout Session 紐付け CAS DataJpaTest")
class BillingContractCheckoutSessionDataJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2028, 2, 10, 12, 0, 0);
    private static final String SESSION = "cs_test_1";
    private static final String OTHER_SESSION = "cs_test_2";

    @Autowired private BillingContractRepository repository;

    @Test
    @DisplayName("BC-13: PENDING かつ未紐付けの契約にだけ Session を紐付けられる")
    void attach_未紐付けPENDINGにだけ成立する() {
        BillingContractEntity pending = repository.saveAndFlush(contract(ContractStatus.PENDING));

        int attached = repository.attachCheckoutSessionIfPending(pending.getId(), SESSION, NOW);

        assertThat(attached).isEqualTo(1);
        assertThat(repository.findById(pending.getId()).orElseThrow().getStripeCheckoutSessionRef())
                .isEqualTo(SESSION);
    }

    @Test
    @DisplayName("BC-23: 同一 session ref の再送は冪等に通る（二重 Session 作成の判定材料になる）")
    void attach_同一sessionの再送は冪等に通る() {
        BillingContractEntity pending = repository.saveAndFlush(contract(ContractStatus.PENDING));

        int first = repository.attachCheckoutSessionIfPending(pending.getId(), SESSION, NOW);
        int replay = repository.attachCheckoutSessionIfPending(pending.getId(), SESSION, NOW);

        assertThat(first).isEqualTo(1);
        assertThat(replay).isEqualTo(1);
        assertThat(repository.findById(pending.getId()).orElseThrow().getStripeCheckoutSessionRef())
                .isEqualTo(SESSION);
    }

    @Test
    @DisplayName("BC-23: 既に別 session を持つ契約は弾き、既存 ref を上書きしない")
    void attach_別sessionを持つ契約は弾く() {
        BillingContractEntity pending = repository.saveAndFlush(contract(ContractStatus.PENDING));
        assertThat(repository.attachCheckoutSessionIfPending(pending.getId(), SESSION, NOW)).isEqualTo(1);

        int rejected = repository.attachCheckoutSessionIfPending(pending.getId(), OTHER_SESSION, NOW);

        assertThat(rejected).isZero();
        assertThat(repository.findById(pending.getId()).orElseThrow().getStripeCheckoutSessionRef())
                .isEqualTo(SESSION);
    }

    @Test
    @DisplayName("BC-23: PENDING でない契約は弾く")
    void attach_PENDINGでない契約は弾く() {
        BillingContractEntity active = repository.saveAndFlush(contract(ContractStatus.ACTIVE));
        BillingContractEntity cancelled = repository.saveAndFlush(contract(ContractStatus.CANCELLED));

        assertThat(repository.attachCheckoutSessionIfPending(active.getId(), SESSION, NOW)).isZero();
        assertThat(repository.attachCheckoutSessionIfPending(cancelled.getId(), OTHER_SESSION, NOW)).isZero();
        assertThat(repository.findById(active.getId()).orElseThrow().getStripeCheckoutSessionRef()).isNull();
        assertThat(repository.findById(cancelled.getId()).orElseThrow().getStripeCheckoutSessionRef()).isNull();
    }

    @Test
    @DisplayName("BC-23: 論理削除済み契約と不在 id は弾く")
    void attach_論理削除と不在は弾く() {
        BillingContractEntity deleted = contract(ContractStatus.PENDING);
        deleted.setDeletedAt(NOW.minusDays(1));
        repository.saveAndFlush(deleted);

        assertThat(repository.attachCheckoutSessionIfPending(deleted.getId(), SESSION, NOW)).isZero();
        assertThat(repository.attachCheckoutSessionIfPending(
                UUID.fromString("01999d74-5130-7000-8000-0000000000ff"), OTHER_SESSION, NOW)).isZero();
        assertThat(repository.findById(deleted.getId()).orElseThrow().getStripeCheckoutSessionRef()).isNull();
    }

    private BillingContractEntity contract(ContractStatus status) {
        return BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(91L)
                .organizationId(5L)
                .contractKind(ContractKind.PLAN)
                .planKey("PRO")
                .status(status)
                .memberCountSnapshot(21)
                .priceJpySnapshot(19800)
                .version(0L)
                .contractedAt(NOW.minusDays(1))
                .createdBy(7L)
                .build();
    }
}
