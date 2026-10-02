package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * 試練A（第2隊）: AC-14 / AC-17b — <b>実 MySQL の並行実行</b>でしか測れない排他を測る。
 *
 * <p><b>なぜ逐次では測れないのか</b>: 「片方だけ成功する」「二重に起きない」は、2つの要求が
 * <b>同時に</b> DB へ到達したときにだけ偽になりうる。単一スレッドで2回呼ぶテストは、1回目が commit
 * された後に2回目が走るため、排他が一切実装されていなくても（例えば「存在チェック→INSERT」の
 * 素朴な実装でも）緑になる。そこで {@link CyclicBarrier} で2スレッドを同一地点まで揃えてから
 * 同時に放ち、commit 後の DB の行数で結果を測る。</p>
 *
 * <p>本クラスに {@code @Transactional} は付けない（テストがトランザクションを握ると、別スレッドの
 * 接続からは一切見えず、競合そのものが発生しない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練A: AC-14/AC-17b operation の並行排他（実MySQL）")
class BillingContractOperationConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final String REQUEST_HASH = "c".repeat(64);
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private Long actorUserId;
    private UUID customerId;
    private UUID contractId;
    private Long contractVersion;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 720_000_000L;
        actorUserId = scopeId;
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            contractId = insertContract();
            entityManager.flush();
            entityManager.clear();
            contractVersion = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                    .orElseThrow().getVersion();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC-14: 同一 contract へ二つの mutation を並行で投げると片方だけ成功し、他方は 409 になる")
    void concurrentMutationsLeaveExactlyOneOperation() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Outcome> outcomes = runConcurrently(
                () -> attemptReserve(barrier, BillingOperationKind.CANCEL),
                () -> attemptReserve(barrier, BillingOperationKind.RESUME));

        long succeeded = outcomes.stream().filter(Outcome::success).count();
        assertThat(succeeded).as("同時到達した2要求のうち成功は1件でなければならない").isEqualTo(1);

        Outcome rejected = outcomes.stream().filter(outcome -> !outcome.success()).findFirst()
                .orElseThrow(() -> new AssertionError("両方成功している = 排他が効いていない"));
        assertThat(rejected.failure()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) rejected.failure()).getErrorCode().getCode())
                .as("pointer 競合の 409 は ENTITLEMENT_021 を用いる（AC-38）")
                .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

        assertThat(operationCount()).as("敗者の operation 行が残ってはならない").isEqualTo(1);
        assertThat(pointerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-17b: 検疫中の契約へ purge と webhook が同時に到達しても、終端化と pointer 削除が二重に起きない")
    void concurrentSystemPathsTerminateExactlyOnce() throws Exception {
        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                contractId, BillingOperationKind.CANCEL, contractVersion,
                BillingOperationActorKind.USER, actorUserId, REQUEST_HASH));
        sagaService.markCallingStripe(reservation.operationId());
        sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");
        assertThat(pointerCount()).as("前提: 検疫は pointer を保持している").isEqualTo(1);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Outcome> outcomes = runConcurrently(
                () -> attemptSystemRelease(barrier),
                () -> attemptSystemRelease(barrier));

        assertThat(outcomes).allMatch(Outcome::success,
                "検疫貫通は purge / webhook のどちらからも例外にしてはならない（D3）");
        int terminatedTotal = outcomes.stream().mapToInt(Outcome::terminatedCount).sum();
        assertThat(terminatedTotal)
                .as("同じ operation の終端化が二重に走ってはならない").isEqualTo(1);

        BillingContractOperationEntity operation = reloadOperation(reservation.operationId());
        assertThat(operation.getStatus()).isEqualTo(BillingOperationStatus.CANCELLED);
        assertThat(pointerCount()).as("孤児を残さず pointer が削除されている").isZero();
        assertThat(nonTerminalOperationCount())
                .as("非終端 operation が残っていてはならない（孤児化の禁止）").isZero();
    }

    // ================================================================
    // 並行実行のヘルパ
    // ================================================================

    /**
     * 2つの試行を同時に放ち、両方の結果を集める。
     *
     * @param first  1つ目の試行
     * @param second 2つ目の試行
     * @return 両方の結果
     * @throws Exception 実行待ちに失敗したとき
     */
    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = executor.submit(first);
            Future<Outcome> b = executor.submit(second);
            List<Outcome> outcomes = new ArrayList<>();
            outcomes.add(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            outcomes.add(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome attemptReserve(CyclicBarrier barrier, BillingOperationKind kind) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sagaService.reserve(new ReserveCommand(contractId, kind, contractVersion,
                    BillingOperationActorKind.USER, actorUserId, REQUEST_HASH));
            return new Outcome(true, null, 0);
        } catch (RuntimeException e) {
            return new Outcome(false, e, 0);
        } catch (Exception e) {
            throw new IllegalStateException("並行試行の待ち合わせに失敗した", e);
        }
    }

    private Outcome attemptSystemRelease(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int terminated = sagaService.terminateNonTerminalAndRelease(contractId);
            return new Outcome(true, null, terminated);
        } catch (RuntimeException e) {
            return new Outcome(false, e, 0);
        } catch (Exception e) {
            throw new IllegalStateException("並行試行の待ち合わせに失敗した", e);
        }
    }

    /**
     * 並行試行1本の結果。
     *
     * @param success         例外なく完了したか
     * @param failure         失敗時の例外（成功時は {@code null}）
     * @param terminatedCount 終端化した operation 件数
     */
    private record Outcome(boolean success, Throwable failure, int terminatedCount) {
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_conc_" + scopeId)
                .status("ACTIVE")
                .provisionAttempts(0)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        entityManager.persist(customer);
        entityManager.flush();
        return customer.getId();
    }

    private UUID insertContract() {
        return billingContractRepository.save(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(customerId)
                .contractedAt(LocalDateTime.now(clock).minusDays(10))
                .currentPeriodEnd(LocalDateTime.now(clock).plusDays(20).truncatedTo(ChronoUnit.SECONDS))
                .createdBy(actorUserId)
                .payerUserId(actorUserId)
                .pspSubscriptionRef("sub_conc_" + scopeId)
                .build()).getId();
    }

    private long operationCount() {
        return countBy("SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c");
    }

    private long nonTerminalOperationCount() {
        return countBy("SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c"
                + " AND status IN ('CREATED','CALLING_STRIPE','RECONCILIATION_REQUIRED')");
    }

    private long pointerCount() {
        return countBy("SELECT COUNT(*) FROM active_billing_contract_operation_pointers"
                + " WHERE contract_id = :c");
    }

    private long countBy(String sql) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(sql)
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private BillingContractOperationEntity reloadOperation(UUID operationId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId).orElseThrow();
        });
    }
}
