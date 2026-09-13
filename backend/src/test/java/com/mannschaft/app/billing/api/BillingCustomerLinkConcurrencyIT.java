package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingContractOperationSagaService;
import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6a — Codex 検分 P2-2: {@code billing_customers} の引き上げが並行しても
 * 予約（tx1）を巻き込んで死なないことを<b>実 MySQL</b> で測る。
 *
 * <h2>なぜモックでは測れないか</h2>
 * <p>この欠陥は2つの機構の組み合わせでしか現れない。(1) {@code uk_bcu_scope} の UNIQUE 違反が
 * 実際に起きること、(2) その例外が<b>参加中のトランザクションを rollback-only にする</b>こと。
 * どちらも Spring / JDBC / MySQL の実物が居て初めて成立し、モックでは例外を投げる真似しかできない
 * （rollback-only フラグは立たないので、壊れた実装でも緑になる）。よって IT でしか書けない。</p>
 *
 * <h2>検体の作り方</h2>
 * <p>同一 scope（同じ USER）に<b>2つの契約</b>を置き、どちらも {@code billing_customer_id} を
 * 持たない状態にする（F20.1 決済フロー由来の契約の形）。両方を同時に予約すると、双方が
 * 「自分の scope の {@code billing_customers} がまだ無い」と観測して引き上げに進み、
 * 片方は必ず UNIQUE 違反になる。</p>
 *
 * <p><b>壊れた実装ではどうなるか</b>: 敗者側の tx1 は rollback-only のまま commit へ進み、
 * {@code UnexpectedRollbackException} で予約が失敗する（operation も pointer も残らない）。
 * したがって「2件とも予約が成立する」ことを測れば、修正の有無を区別できる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a P2-2: billing_customers 引き上げの並行競合（実MySQL）")
class BillingCustomerLinkConcurrencyIT extends AbstractBillingCancelResumeApiIT {

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private BillingCustomerJpaRepository billingCustomerJpaRepository;

    private Long userId;
    private LocalDateTime periodEnd;

    @BeforeEach
    void setUp() {
        userId = insertUser("linkrace");
        periodEnd = LocalDateTime.now(clock).plusDays(20).withNano(0);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "DELETE FROM billing_customers WHERE scope_id = :id")
                        .setParameter("id", userId).executeUpdate());
    }

    @Test
    @DisplayName("P2-2: 同一scopeの未紐付け契約2件を同時に予約しても両方成功し、billing_customers は1行だけできる")
    void concurrentReservationsSurviveUniqueCollision() throws Exception {
        UUID contractA = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY,
                "sub_race_a_" + userId, periodEnd, null);
        UUID contractB = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY,
                "sub_race_b_" + userId, periodEnd, null);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(reserveTask(contractA, barrier));
            Future<Outcome> b = pool.submit(reserveTask(contractB, barrier));
            Outcome first = a.get(60, TimeUnit.SECONDS);
            Outcome second = b.get(60, TimeUnit.SECONDS);

            assertThat(List.of(first, second))
                    .as("引き上げの UNIQUE 競合で予約（tx1）が道連れになってはならない。"
                            + "失敗した側の理由: %s / %s", first.failure(), second.failure())
                    .allMatch(Outcome::reserved);
        } finally {
            pool.shutdownNow();
        }

        // 勝者の1行だけが残る（敗者は読み直して同じ行を使う）。
        assertThat(billingCustomerJpaRepository
                .findByScopeKindAndScopeId(EntitlementScopeKind.USER, userId))
                .as("scope ごとに高々1行（uk_bcu_scope）").isPresent();

        // 予約が本当に成立している＝operation と pointer が実在する（空虚な緑の排除）。
        assertThat(pointerRepository.findById(contractA)).as("契約Aの lease").isPresent();
        assertThat(pointerRepository.findById(contractB)).as("契約Bの lease").isPresent();
        assertThat(operationRepository.findByContractIdAndStatusAndDeletedAtIsNull(
                contractA, BillingOperationStatus.CREATED)).as("契約Aの operation").hasSize(1);
        assertThat(operationRepository.findByContractIdAndStatusAndDeletedAtIsNull(
                contractB, BillingOperationStatus.CREATED)).as("契約Bの operation").hasSize(1);

        // 両契約とも欠けていた紐付けが修復されている。
        assertThat(reloadContract(contractA).getBillingCustomerId()).isNotNull();
        assertThat(reloadContract(contractB).getBillingCustomerId()).isNotNull();
        assertThat(reloadContract(contractA).getBillingCustomerId())
                .as("同一 scope なので同じ Customer を指す")
                .isEqualTo(reloadContract(contractB).getBillingCustomerId());
    }

    @Test
    @DisplayName("P2-2: 陽性対照 — 既にbilling_customersがある契約の予約はそのまま成功する（過剰な引き上げをしない）")
    void reservationReusesExistingCustomer() {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY,
                "sub_race_solo_" + userId, periodEnd, null);

        Outcome outcome = reserve(contractId);
        assertThat(outcome.reserved()).as("理由: %s", outcome.failure()).isTrue();
        UUID firstCustomerId = reloadContract(contractId).getBillingCustomerId();
        assertThat(firstCustomerId).isNotNull();

        // lease を解放して2度目の予約を行う（既存 Customer が再利用されること）。
        sagaService.cancelAndRelease(outcome.operationId(), "TEST_RELEASE");

        Outcome again = reserve(contractId);
        assertThat(again.reserved()).as("理由: %s", again.failure()).isTrue();
        assertThat(reloadContract(contractId).getBillingCustomerId())
                .as("2度目は引き上げず既存行を使う").isEqualTo(firstCustomerId);
        assertThat(billingCustomerJpaRepository
                .findByScopeKindAndScopeId(EntitlementScopeKind.USER, userId)).isPresent();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** 予約の結果（例外は握らず、理由をテストの失敗メッセージへ運ぶ）。 */
    private record Outcome(boolean reserved, UUID operationId, String failure) { }

    private Callable<Outcome> reserveTask(UUID contractId, CyclicBarrier barrier) {
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return reserve(contractId);
        };
    }

    private Outcome reserve(UUID contractId) {
        try {
            OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.CANCEL, null,
                    BillingOperationActorKind.USER, userId, null));
            return new Outcome(true, reservation.operationId(), null);
        } catch (RuntimeException e) {
            return new Outcome(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
