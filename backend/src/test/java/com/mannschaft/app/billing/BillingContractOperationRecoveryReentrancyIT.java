package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCustomerEntity;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練D（第4b隊）: <b>AC-82</b> — 回収が同じ operation に<b>二重に走らない</b>ことを
 * 実 MySQL の並行実行と再入で測る。
 *
 * <h2>なぜ逐次では測れないのか</h2>
 * <p>「pointer が二度解放されない」は、2つの回収が<b>同時に</b>同じ行へ到達したときにだけ偽に
 * なりうる。単一スレッドで2回呼ぶテストは、1回目が commit された後に2回目が走るため、
 * 排他が一切実装されていない素朴な実装（存在チェック→DELETE）でも緑になる。
 * そこで {@link CyclicBarrier} で2スレッドを揃えてから同時に放ち、
 * 「回収したと申告した回数」の合計と commit 後の行で測る（試練A の
 * {@code BillingContractOperationConcurrencyIT} と同じ流儀）。</p>
 *
 * <p>再入（逐次2回目が no-op であること）も対で測る。並行だけを測ると、
 * 「2回目に例外を投げて落ちる」実装が「二重には起きていない」として通ってしまう。</p>
 *
 * <p>{@code @Transactional} は付けない（テストが tx を握ると別スレッドの接続から一切見えず、
 * 競合そのものが発生しない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練D: AC-82 回収の再入・並行で二重に走らない（実MySQL）")
class BillingContractOperationRecoveryReentrancyIT extends AbstractMySqlIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20L;
    private static final long STALE_MINUTES = 60L;

    @Autowired private BillingContractOperationRecoveryService recoveryService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private UUID customerId;
    private UUID contractId;
    private UUID operationId;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 760_000_000L;
        String subscriptionRef = "sub_reentrant_" + scopeId;
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            contractId = insertContract(subscriptionRef);
        });
        operationId = insertStaleCallingStripeOperation();
        // 停止窓(b): Stripe には痕跡があり実物も反映済み（回収すると pointer を解放する経路）。
        Mockito.when(billingPaymentGateway.findOperationIdOnSubscription(subscriptionRef))
                .thenReturn(Optional.of(operationId));
        Instant periodEnd = LocalDateTime.now(clock).plusDays(20)
                .truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC);
        Mockito.when(billingPaymentGateway.retrieveSubscription(subscriptionRef))
                .thenReturn(new BillingPaymentGateway.SubscriptionSnapshot(
                        subscriptionRef, "active", true,
                        periodEnd.minus(30, ChronoUnit.DAYS), periodEnd, null));
        assertThat(pointerCount()).as("前提: 回収前は pointer が1行ある").isEqualTo(1);
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
    @DisplayName("AC-82: 同じ operation へ2本の回収が同時到達しても、回収したと申告するのは1本だけで pointer は一度しか解放されない")
    void concurrentRecoveryRecoversExactlyOnce() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Outcome> outcomes = runConcurrently(
                () -> attemptRecover(barrier), () -> attemptRecover(barrier));

        assertThat(outcomes).allMatch(Outcome::completed,
                "回収は競合しても例外で落ちてはならない（次周で拾えなくなる）");
        long recoveredCount = outcomes.stream().filter(Outcome::recovered).count();
        assertThat(recoveredCount)
                .as("同じ operation の回収が二重に走ってはならない").isEqualTo(1);

        assertThat(reloadOperation().getStatus()).isEqualTo(BillingOperationStatus.APPLIED);
        assertThat(pointerCount()).isZero();
    }

    @Test
    @DisplayName("AC-82: 回収後に同じ operation をもう一度回収しても何も起きない（再入で false・行は変わらない）")
    void secondRecoveryIsNoOp() {
        assertThat(recoveryService.recoverOperation(operationId))
                .as("1回目は実際に回収する（陽性対照）").isTrue();
        Instant afterFirst = reloadOperation().getUpdatedAt();

        assertThat(recoveryService.recoverOperation(operationId))
                .as("2回目は何も回収しない").isFalse();

        assertThat(reloadOperation().getStatus()).isEqualTo(BillingOperationStatus.APPLIED);
        assertThat(reloadOperation().getUpdatedAt())
                .as("2回目が行を触ってはならない").isEqualTo(afterFirst);
        assertThat(pointerCount()).isZero();
    }

    @Test
    @DisplayName("AC-82: 回収1周を2度走らせても2周目は0件（走査の再入でも pointer が二度解放されない）")
    void secondScanRecoversNothing() {
        assertThat(recoveryService.recoverStaleOperations().recovered())
                .as("1周目は回収する（陽性対照）").isEqualTo(1);

        assertThat(recoveryService.recoverStaleOperations().recovered()).isZero();
        assertThat(pointerCount()).isZero();
    }

    // ================================================================
    // 並行実行のヘルパ
    // ================================================================

    /**
     * 回収の試行結果。
     *
     * @param completed 例外なく終わったか
     * @param recovered 本呼び出しが実際に回収したと申告したか
     */
    private record Outcome(boolean completed, boolean recovered) {}

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

    private Outcome attemptRecover(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Outcome(true, recoveryService.recoverOperation(operationId));
        } catch (RuntimeException e) {
            return new Outcome(false, false);
        } catch (Exception e) {
            throw new IllegalStateException("並行試行の待ち合わせに失敗した", e);
        }
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    private UUID insertStaleCallingStripeOperation() {
        String marker = "b".repeat(64);
        UUID id = transactionTemplate.execute(tx -> {
            BillingContractOperationEntity operation = operationRepository.save(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.CANCEL)
                            .status(BillingOperationStatus.CALLING_STRIPE)
                            .step(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash(marker)
                            .stripeSubscriptionRef("sub_reentrant_" + scopeId)
                            .version(0L)
                            .actorKind(BillingOperationActorKind.USER)
                            .createdBy(scopeId)
                            .build());
            entityManager.flush();
            pointerRepository.save(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId)
                    .operationId(operation.getId())
                    .build());
            entityManager.flush();
            return operation.getId();
        });
        // updated_at / created_at は Instant 列（日時方針 §1）。ネイティブ更新でもエンティティと
        // 同じ格納基準（hibernate.jdbc.time_zone=UTC）に乗るよう Instant で束縛する
        // （ゾーンを持たない日時で束縛すると JVM 既定 TZ の壁時計がそのまま入り、
        //  エンティティ経由の書き込みと 9 時間ずれて stale 判定が別の理由で当たる）。
        Instant staleAt = Instant.now(clock)
                .minus(java.time.Duration.ofMinutes(STALE_MINUTES)).truncatedTo(ChronoUnit.SECONDS);
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "UPDATE billing_contract_operations "
                                + "SET created_at = :ts, updated_at = :ts, idempotency_key = :key "
                                + "WHERE request_hash = :marker AND contract_id = :c")
                .setParameter("ts", staleAt)
                .setParameter("key", id.toString())
                .setParameter("marker", marker)
                .setParameter("c", contractId)
                .executeUpdate());
        return id;
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_reentrant_" + scopeId)
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

    private UUID insertContract(String subscriptionRef) {
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
                .createdBy(scopeId)
                .payerUserId(scopeId)
                .pspSubscriptionRef(subscriptionRef)
                .build()).getId();
    }

    private long pointerCount() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private BillingContractOperationEntity reloadOperation() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId).orElseThrow();
        });
    }
}
