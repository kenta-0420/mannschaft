package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractOperationRecoveryService.RecoveryOutcome;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練D（第4b隊）: <b>AC-78 / AC-79 / AC-80 / AC-81 / AC-84</b> — プロセス停止窓の回収を
 * <b>実 MySQL</b>で測る（D8）。
 *
 * <h2>この群で最も注意すべき「空虚な緑」</h2>
 * <p>回収の実装が無い段階では、「走査対象が1件も見つからないから何も壊れない」形のテストが
 * 簡単に書けてしまう（{@code recoverStaleOperations()} が 0 件を返して緑、という類）。
 * そこで本クラスは<b>必ず先に stale な行を実際に作る</b>。
 * 作った行は {@code @BeforeEach} ではなく各テスト内で作り、作った直後に
 * 「前提: いま確かに CALLING_STRIPE と pointer がある」ことを assert してから回収を走らせる。
 * 前提の assert が落ちればフィクスチャの欠陥であり、回収の欠陥と混ざらない。</p>
 *
 * <p><b>陽性/陰性対照</b>: AC-81 は「しきい値未満は触られない」を対で置く。AC-84 は回収前後の
 * operation 行数を数える（回収が自分用の operation を起票すると pointer 自縄自縛になる）。</p>
 *
 * <p><b>{@code @Transactional} を付けないのは意図である</b>。回収は tx を分けて走る補償処理であり、
 * テストが tx を握ると commit が起きず、pointer の解放も別接続から観測できない。後片付けは
 * {@link #tearDown()} が行う（試練A の {@code BillingContractOperationSagaIT} と同じ流儀）。</p>
 *
 * <p><b>stale の作り方</b>: {@code updated_at} は Entity の {@code @PreUpdate} が
 * {@code LocalDateTime.now()} で上書きしてしまうため、JPA 経由では過去に置けない。
 * そこで native UPDATE で {@code created_at}/{@code updated_at} を直に過去へ倒す（実 DB の行が
 * 本当に古いという状態を作る）。</p>
 *
 * <p>しきい値の判定式そのものは固定 Clock の純 UT
 * {@code BillingOperationStaleThresholdTest} が、再入・並行は
 * {@code BillingContractOperationRecoveryReentrancyIT}（AC-82）が担う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練D: PR6a 停止窓の回収（実MySQL）")
class BillingContractOperationRecoveryIT extends AbstractMySqlIntegrationTest {

    /** しきい値（5分）を確実に超える古さ。 */
    private static final long STALE_MINUTES = 60L;
    /** しきい値未満（進行中の正常な operation）。 */
    private static final long FRESH_MINUTES = 1L;

    @Autowired private BillingContractOperationRecoveryService recoveryService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    /** Stripe は叩かせない。回収の判定材料（metadata の痕跡と実物の反映）をここから与える。 */
    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 730_000_000L;
        transactionTemplate.executeWithoutResult(tx -> customerId = insertCustomer());
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

    // ================================================================
    // AC-78 停止窓(a): tx1 commit 後・Stripe 呼び出し前に落ちた
    // ================================================================

    @Nested
    @DisplayName("AC-78 停止窓(a) stale な CREATED の回収")
    class WindowA {

        @Test
        @DisplayName("AC-78: Stripe 側に痕跡が無い stale な CREATED は CANCELLED へ終端化され、pointer が解放される")
        void staleCreatedWithoutStripeTraceIsCancelled() {
            Fixture fixture = givenStaleOperation(
                    BillingOperationStatus.CREATED, BillingOperationStep.RECEIVED, STALE_MINUTES);
            // Stripe をまだ呼んでいない = metadata に痕跡が無い（AC-77 の読み戻しが判定材料）。
            givenStripe(fixture.subscriptionRef, Optional.empty(), false);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.cancelledStale()).isEqualTo(1);
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CANCELLED);
            assertThat(pointerCount(fixture.contractId))
                    .as("永久残留させない。pointer を解放しなければ利用者は二度と解約できない")
                    .isZero();
        }

        @Test
        @DisplayName("AC-78: CANCELLED へ倒した契約の cancelled_at は書かれない（Stripe を呼んでいないのだから解約は成立していない）")
        void cancelledRecoveryDoesNotTouchContract() {
            Fixture fixture = givenStaleOperation(
                    BillingOperationStatus.CREATED, BillingOperationStep.RECEIVED, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.empty(), false);

            recoveryService.recoverStaleOperations();

            assertThat(reloadContract(fixture.contractId).getCancelledAt()).isNull();
        }
    }

    // ================================================================
    // AC-79 停止窓(b): Stripe 成功後・tx2 開始前に落ちた
    // ================================================================

    @Nested
    @DisplayName("AC-79 停止窓(b) stale な CALLING_STRIPE（Stripe は反映済み）の回収")
    class WindowB {

        @Test
        @DisplayName("AC-79: Stripe 実物が反映済みなら APPLIED として tx2 相当を完了させ、pointer を解放する")
        void staleCallingStripeWithAppliedStripeBecomesApplied() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.appliedFromStripe()).isEqualTo(1);
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.APPLIED);
            assertThat(pointerCount(fixture.contractId)).isZero();
        }

        @Test
        @DisplayName("AC-79: tx2 相当の反映として契約の cancelled_at が入る（利用者の解約を取りこぼさない）")
        void appliedRecoveryReflectsCancellationIntoContract() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);

            recoveryService.recoverStaleOperations();

            assertThat(reloadContract(fixture.contractId).getCancelledAt())
                    .as("Stripe 側では解約が成立している。DB に反映しなければ"
                            + "「利用者は解約したのに解約されていない」状態が固定される")
                    .isNotNull();
        }

        @Test
        @DisplayName("AC-79: 反映済みの停止窓(b) を FAILED にしてはならない（解約の取りこぼしの禁止）")
        void appliedRecoveryIsNeverFailed() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);

            recoveryService.recoverStaleOperations();

            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isNotEqualTo(BillingOperationStatus.FAILED);
        }
    }

    // ================================================================
    // AC-80 停止窓(c): tx2 失敗後・検疫記録前に落ちた
    // ================================================================

    @Nested
    @DisplayName("AC-80 停止窓(c) Stripe と DB が食い違う stale な CALLING_STRIPE")
    class WindowC {

        @Test
        @DisplayName("AC-80: Stripe に痕跡はあるが実物が未反映なら RECONCILIATION_REQUIRED へ倒し、pointer は保持する")
        void staleCallingStripeWithMismatchIsQuarantined() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            // 痕跡あり（=呼んだ）／実物は未反映（=DB と食い違う）。
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), false);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.quarantined()).isEqualTo(1);
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.RECONCILIATION_REQUIRED);
            assertThat(pointerCount(fixture.contractId))
                    .as("検疫は terminal ではない。pointer を保持したまま reconcile を待つ（AC-8）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("AC-80: 検疫へ倒しても契約は変更しない（食い違いを DB へ書き込んで塗り固めない）")
        void quarantineDoesNotMutateContract() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), false);

            recoveryService.recoverStaleOperations();

            assertThat(reloadContract(fixture.contractId).getCancelledAt()).isNull();
        }
    }

    // ================================================================
    // AC-81 しきい値（進行中を横取りしない）
    // ================================================================

    @Nested
    @DisplayName("AC-81 stale しきい値と進行中の保護")
    class StaleThreshold {

        @Test
        @DisplayName("AC-81: しきい値未満の CALLING_STRIPE は1行も回収されず、status も pointer もそのまま残る")
        void freshOperationIsNotTouched() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, FRESH_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.recovered())
                    .as("進行中の正常な operation を回収が横取りしてはならない").isZero();
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
            assertThat(pointerCount(fixture.contractId)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-81: 同条件でしきい値を超えていれば回収される（陽性対照。走査そのものが動いていることの裏取り）")
        void staleCounterpartIsRecovered() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);

            assertThat(recoveryService.recoverStaleOperations().recovered()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-81: 検疫（RECONCILIATION_REQUIRED）は古くても回収が触らない（pointer を保持したまま reconcile を待つ）")
        void quarantinedOperationIsNotScanned() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.RECONCILIATION_REQUIRED,
                    BillingOperationStep.RECONCILE_PENDING, STALE_MINUTES);

            assertThat(recoveryService.recoverStaleOperations().recovered()).isZero();
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.RECONCILIATION_REQUIRED);
            assertThat(pointerCount(fixture.contractId)).isEqualTo(1);
        }
    }

    // ================================================================
    // AC-84 回収は operation を作らない
    // ================================================================

    @Nested
    @DisplayName("AC-84 回収経路は operation を作らない")
    class NoOperationCreated {

        @Test
        @DisplayName("AC-84: 回収1周の前後で operation の行数が増えない（回収が自分の pointer を取りに行って自縄自縛にならない）")
        void recoveryCreatesNoNewOperationRow() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), true);
            long before = operationCount(fixture.contractId);
            assertThat(before).as("前提: 回収対象が1行ある").isEqualTo(1);

            recoveryService.recoverStaleOperations();

            assertThat(operationCount(fixture.contractId))
                    .as("回収は既存 operation を遷移させるだけであり、新しい operation を起票しない")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("AC-84: 検疫へ倒す経路でも operation は増えない（pointer 保持側でも起票しない）")
        void quarantineRecoveryCreatesNoNewOperationRow() {
            Fixture fixture = givenStaleOperation(BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, STALE_MINUTES);
            givenStripe(fixture.subscriptionRef, Optional.of(fixture.operationId), false);

            recoveryService.recoverStaleOperations();

            assertThat(operationCount(fixture.contractId)).isEqualTo(1);
        }
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    /** 1件の stale な operation とその pointer。 */
    private record Fixture(UUID contractId, UUID operationId, String subscriptionRef) {}

    /**
     * stale な operation と pointer を<b>実 DB に作る</b>（空虚な緑の防止の要）。
     *
     * <p>作った直後に「確かにその status の行と pointer がある」ことを assert する。前提が崩れて
     * いれば回収の欠陥ではなくフィクスチャの欠陥であり、赤の理由を混ぜない。</p>
     *
     * @param status     作る operation の status
     * @param step       作る operation の step
     * @param ageMinutes {@code updated_at} を何分過去へ倒すか
     * @return 作ったフィクスチャ
     */
    private Fixture givenStaleOperation(
            BillingOperationStatus status, BillingOperationStep step, long ageMinutes) {
        String marker = markerHash();
        String subscriptionRef = "sub_recover_" + scopeId + "_" + marker.substring(0, 8);
        UUID contractId = transactionTemplate.execute(tx -> insertContract(subscriptionRef));
        UUID operationId = transactionTemplate.execute(tx -> {
            BillingContractOperationEntity operation = operationRepository.save(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.CANCEL)
                            .status(status)
                            .step(step)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash(marker)
                            .stripeSubscriptionRef(subscriptionRef)
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
        // idempotency_key は operationId（AC-32）。@PreUpdate に上書きされない native UPDATE で
        // 揃えつつ、同時に created_at / updated_at を過去へ倒して「本当に古い行」を作る。
        LocalDateTime staleAt = LocalDateTime.now(clock)
                .minusMinutes(ageMinutes).truncatedTo(ChronoUnit.SECONDS);
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "UPDATE billing_contract_operations "
                                + "SET created_at = :ts, updated_at = :ts, idempotency_key = :key "
                                + "WHERE request_hash = :marker")
                .setParameter("ts", staleAt)
                .setParameter("key", operationId.toString())
                .setParameter("marker", marker)
                .executeUpdate());

        BillingContractOperationEntity reloaded = reloadOperation(operationId);
        assertThat(reloaded.getStatus()).as("前提: 狙った status の行を作れている").isEqualTo(status);
        assertThat(reloaded.getUpdatedAt()).as("前提: updated_at が実際に過去へ倒れている")
                .isEqualTo(staleAt);
        assertThat(pointerCount(contractId)).as("前提: pointer が1行ある").isEqualTo(1);
        return new Fixture(contractId, operationId, subscriptionRef);
    }

    /**
     * Stripe 側の状態を与える（回収の判定材料）。
     *
     * @param subscriptionRef   対象 subscription
     * @param traceOperationId  metadata に載っている operationId（空=痕跡なし・AC-77）
     * @param cancelAtPeriodEnd Stripe 実物が反映済みか
     */
    private void givenStripe(
            String subscriptionRef, Optional<UUID> traceOperationId, boolean cancelAtPeriodEnd) {
        Mockito.when(billingPaymentGateway.findOperationIdOnSubscription(subscriptionRef))
                .thenReturn(traceOperationId);
        Instant periodEnd = LocalDateTime.now(clock).plusDays(20)
                .truncatedTo(ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC);
        Mockito.when(billingPaymentGateway.retrieveSubscription(subscriptionRef))
                .thenReturn(new BillingPaymentGateway.SubscriptionSnapshot(
                        subscriptionRef, "active", cancelAtPeriodEnd,
                        periodEnd.minus(30, ChronoUnit.DAYS), periodEnd, null));
    }

    private String markerHash() {
        return String.format("%064x", new java.math.BigInteger(1,
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .substring(0, 64);
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_recover_" + scopeId)
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

    private long operationCount(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private long pointerCount(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id = :c")
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

    private BillingContractEntity reloadContract(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }
}
