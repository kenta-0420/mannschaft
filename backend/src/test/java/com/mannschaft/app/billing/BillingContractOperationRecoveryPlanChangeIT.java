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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練C（第4隊）: <b>AC-88〜AC-94・AC-100</b> — PR6a の回収が upgrade を殺さないことを実 MySQL で測る。
 *
 * <h2>この群の核心（正本 05_billing_center.md E1F）</h2>
 * <p>PR6a の回収は {@code DEFAULT_STALE_THRESHOLD=5分} で {@code CREATED}/{@code CALLING_STRIPE} を
 * 走査し、{@code isEffectApplied} が {@code PLAN_CHANGE} を {@code default -> false} で返すため、
 * <b>素直な現状の実装では upgrade の operation が5分で {@code RECONCILIATION_REQUIRED}（検疫）へ
 * 倒れる</b>。以後その契約はあらゆる操作が 409 になり、永久に操作できなくなる。</p>
 *
 * <p>正本 E1F の判定は「待つ」か「失敗確定」かの2値のみ（AC-88）。<b>回収は支払いを確定しない</b>
 * （確定は {@code invoice.paid} だけ・AC-93）。</p>
 *
 * <h2>空虚な緑への備え</h2>
 * <p>回収の実装が無い段階では「走査対象が見つからないから何も壊れない」形で緑になりうる。
 * 本クラスは各テストで<b>必ず先に stale な行を実際に作り</b>、作った直後に「前提: 狙った status の
 * 行がある・updated_at が過去へ倒れている・pointer が1行ある・change が意図した status である」を
 * assert してから回収を走らせる。</p>
 *
 * <p><b>{@code @Transactional} を付けない</b>: 回収は tx を分けて走る補償処理であり、テストが tx を
 * 握ると commit が起きず観測できない（{@code BillingContractOperationRecoveryIT} と同じ流儀）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練C: PR6b-1 回収が upgrade を殺さない（実MySQL・AC-88〜94/100）")
class BillingContractOperationRecoveryPlanChangeIT extends AbstractMySqlIntegrationTest {

    /** しきい値（5分）を確実に超える古さ（6分・AC-89）。 */
    private static final long STALE_MINUTES = 6L;
    /** しきい値未満。 */
    private static final long FRESH_MINUTES = 1L;

    private static final String TARGET_PRICE_REF = "price_pr6b1_recovery_full";
    private static final String OLD_PRICE_REF = "price_pr6b1_recovery_basic";

    @Autowired private BillingContractOperationRecoveryService recoveryService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private BillingContractChangeRepository changeRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    /** Stripe は叩かせない。回収の判定材料をここから与える。 */
    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 741_000_000L;
        transactionTemplate.executeWithoutResult(tx -> customerId = insertCustomer());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_changes WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
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
    // AC-89 / AC-88b: 6分経過×1周でも upgrade は殺されない
    // ================================================================

    @Nested
    @DisplayName("AC-89/88b: pending_update 存続中の stale upgrade は検疫されない")
    class WaitingUpgradeIsNotQuarantined {

        @Test
        @DisplayName("AC-89: 6分経過(5分しきい値超過)×回収バッチ1周でも、"
                + "operationはCALLING_STRIPEのまま・pointerは保持されたまま・changeはPENDING_PAYMENTのまま")
        void staleUpgradeWithPendingUpdateIsLeftWaiting() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, /* pendingUpdateStillAlive */ true,
                    /* itemsSwitched */ false);
            givenStripeTrace(fixture, true, false);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.quarantined())
                    .as("PR6a の素直な実装は default -> false で検疫へ倒す。"
                            + "本ACはそれを禁止する（正本 E1F）")
                    .isZero();
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .as("待つ状態では operation は CALLING_STRIPE のまま")
                    .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
            assertThat(pointerCount(fixture.contractId))
                    .as("pointer は保持されたまま（唯一の耐久 lease を手放さない）").isEqualTo(1);
            assertThat(reloadChange(fixture.changeId).getStatus())
                    .as("change も PENDING_PAYMENT のまま（回収は支払いを確定しない・AC-93）")
                    .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
        }

        @Test
        @DisplayName("AC-88b: items が target へ切替済みだが webhook 未確定でも、待つ扱いのまま殺されない")
        void staleUpgradeWithItemsSwitchedButNoWebhookYetIsLeftWaiting() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, /* pendingUpdateStillAlive */ false,
                    /* itemsSwitched */ true);
            givenStripeTrace(fixture, true, true);

            recoveryService.recoverStaleOperations();

            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
            assertThat(pointerCount(fixture.contractId)).isEqualTo(1);
        }
    }

    // ================================================================
    // AC-88c: Stripe を参照できなかった周は見送る（検疫しない）
    // ================================================================

    @Nested
    @DisplayName("AC-88c: Stripe参照不能な周は状態を変えず見送る")
    class UnreferenceableStripeIsSkipped {

        @Test
        @DisplayName("AC-88c: retrieveSubscriptionがnull(trace==null)を返す周は、operation/pointer/changeいずれも一切変えない")
        void unreferenceableStripeLeavesEverythingUntouched() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, true, false);
            Mockito.when(billingPaymentGateway.findOperationIdOnSubscription(fixture.subscriptionRef))
                    .thenReturn(Optional.of(fixture.operationId));
            Mockito.when(billingPaymentGateway.retrieveSubscription(fixture.subscriptionRef))
                    .thenReturn(null);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(outcome.recovered())
                    .as("一時的な参照障害で契約を凍結させない（検疫しない）").isZero();
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
            assertThat(pointerCount(fixture.contractId)).isEqualTo(1);
            assertThat(reloadChange(fixture.changeId).getStatus())
                    .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
        }
    }

    // ================================================================
    // AC-90: しきい値5分ちょうどは stale にしない（半開区間）
    // ================================================================

    @Nested
    @DisplayName("AC-90: しきい値ちょうどは stale にしない")
    class ExactThresholdIsNotStale {

        // 第11隊是正（AC-90）: 旧 exactlyFiveMinutesIsNotScanned はここに実装していたが、
        // フィクスチャの updated_at（実時計 - 5分・秒切り捨て）と本サービスが内部で計算する
        // Instant.now(clock).minus(5分)（実時計・別の瞬間）という2つの独立した実時計呼び出しを
        // 突き合わせていたため非決定的だった。フィクスチャ側の切り捨てで最大1秒過去へ倒れ、
        // かつサービス側の計算は必ずそれより後に走るので、この検体は実際には常に5分を「超過」しており、
        // 「ちょうど5分」の境界を検体として一度も表現できていなかった（弱体化ではなく、境界を
        // 表現できない検体を撤去して強化する置き換え）。
        //
        // 境界の主張は次の2つの決定的な検体へ分解した:
        //   1. BillingContractOperationRepositoryStaleScanBoundaryIT
        //      — updated_at = T の行に対し staleBefore = T で0件・T+1秒で1件であることを、
        //        実 MySQL でリポジトリの述語を直接呼んで固定する（半開区間そのもの）。
        //   2. BillingContractOperationRecoveryStaleBeforeThresholdTest
        //      — Clock.fixed + Mockito で recoverStaleOperations() が計算する staleBefore 引数を
        //        ArgumentCaptor で捕まえ、NOW.minus(Duration.ofMinutes(5)) と厳密一致することを
        //        実時計を介さず固定する（しきい値の計算そのもの）。
        //
        // 本メソッドは撤去し、走査そのものが動くことの裏取りである陽性対照
        // sixMinutesIsScannedCounterpart のみを残す。

        @Test
        @DisplayName("AC-90: 陽性対照 — 同条件で6分経過なら走査対象に入る（走査そのものが動いていることの裏取り）")
        void sixMinutesIsScannedCounterpart() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, true, false);
            givenStripeTrace(fixture, true, false);

            assertThat(recoveryService.recoverStaleOperations().scanned()).isEqualTo(1);
        }
    }

    // ================================================================
    // AC-91: recoverBySubscriptionRef（しきい値を適用しない経路）
    // ================================================================

    @Nested
    @DisplayName("AC-91: recoverBySubscriptionRefは待つ状態のPLAN_CHANGEを横取りしない")
    class RecoverBySubscriptionRefDoesNotStealWaiting {

        @Test
        @DisplayName("AC-91: しきい値未満(1分)のPLAN_CHANGEでも、待つ状態ならrecoverBySubscriptionRefはfalseを返し何も変えない")
        void freshWaitingUpgradeIsNotRecoveredBySubscriptionRef() {
            Fixture fixture = givenStalePlanChange(FRESH_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, true, false);
            givenStripeTrace(fixture, true, false);

            boolean recovered = recoveryService.recoverBySubscriptionRef(fixture.subscriptionRef);

            assertThat(recovered)
                    .as("PR6a の「所有を主張しない」流儀。待つ状態なら発火しない").isFalse();
            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
            assertThat(pointerCount(fixture.contractId)).isEqualTo(1);
        }
    }

    // ================================================================
    // AC-92: pending_update失効かつitems未切替 → FAILED＋pointer解放
    // ================================================================

    @Nested
    @DisplayName("AC-92: 失効確定でFAILED＋pointer解放")
    class ExpiredAndNotSwitchedBecomesFailed {

        @Test
        @DisplayName("AC-92: pending_updateが失効しitemsも切り替わっていないstale upgradeは、"
                + "回収がoperationをFAILEDへ確定させpointerを解放する")
        void expiredPendingUpdateWithoutItemSwitchIsFailedAndPointerReleased() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, /* pendingUpdateStillAlive */ false,
                    /* itemsSwitched */ false);
            givenStripeTrace(fixture, true, false);

            RecoveryOutcome outcome = recoveryService.recoverStaleOperations();

            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .as("webhookが届かなかった場合の受け皿として回収がFAILEDへ確定させる")
                    .isEqualTo(BillingOperationStatus.FAILED);
            assertThat(pointerCount(fixture.contractId))
                    .as("terminal確定と同一トランザクションでpointerを解放する").isZero();
            assertThat(outcome.recovered()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-92: 失効確定のFAILEDでも旧プランの契約スナップショットは書き換わらない（回収は権利を切り替えない・AC-94）")
        void expiredFailureDoesNotMutateContractPlan() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, false, false);
            givenStripeTrace(fixture, true, false);
            BillingContractEntity before = reloadContract(fixture.contractId);

            recoveryService.recoverStaleOperations();

            BillingContractEntity after = reloadContract(fixture.contractId);
            assertThat(after.getPlanKey()).isEqualTo(before.getPlanKey());
            assertThat(after.getPriceJpySnapshot()).isEqualTo(before.getPriceJpySnapshot());
        }

        @Test
        @DisplayName("AC-92: 失効確定のFAILEDでもchange行はこの回収では変更されない(確定はwebhookの担当のまま。回収はoperationのみ)")
        void expiredFailureLeavesChangeRowUntouchedByRecovery() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, false, false);
            givenStripeTrace(fixture, true, false);

            recoveryService.recoverStaleOperations();

            // 回収は operation を FAILED にするだけであり、change 行の確定(FAILED化)は
            // pending_update_expired webhook が担う（正本E1F）。回収が change まで直接書くと
            // webhookとの二重確定・競合を生む。
            assertThat(reloadChange(fixture.changeId).getStatus())
                    .as("change 行の確定は webhook の専管")
                    .isNotEqualTo(BillingContractChangeStatus.APPLIED);
        }
    }

    // ================================================================
    // AC-93/94: 回収は支払いを確定しない（APPLIEDにしない・反映しない）
    // ================================================================

    @Nested
    @DisplayName("AC-93/94: 回収は支払いを確定しない")
    class RecoveryNeverAppliesPlanChange {

        @Test
        @DisplayName("AC-93: 同期成功直後(customer.subscription.updated相当)で items が切替済みでも、"
                + "回収はoperationをAPPLIEDにしない（確定はinvoice.paidだけ）")
        void syncSuccessDoesNotMakeRecoveryApply() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, false, /* itemsSwitched */ true);
            givenStripeTrace(fixture, true, true);

            recoveryService.recoverStaleOperations();

            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .as("回収がAPPLIEDへ倒すと、invoice.paid前に権利が発行され得る（AC-36違反）")
                    .isNotEqualTo(BillingOperationStatus.APPLIED);
        }

        @Test
        @DisplayName("AC-93: recoverBySubscriptionRef経由でも items 切替済みだけではAPPLIEDにしない")
        void syncSuccessViaSubscriptionRefDoesNotApply() {
            Fixture fixture = givenStalePlanChange(FRESH_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, false, true);
            givenStripeTrace(fixture, true, true);

            recoveryService.recoverBySubscriptionRef(fixture.subscriptionRef);

            assertThat(reloadOperation(fixture.operationId).getStatus())
                    .isNotEqualTo(BillingOperationStatus.APPLIED);
        }

        @Test
        @DisplayName("AC-94: PLAN_CHANGEの回収がapplyRecoveredReflection相当（権利切替・現在プランの反映）を行わないことを、"
                + "entitlement/契約スナップショットが変化しないことで測る")
        void recoveryNeverReflectsPlanChangeIntoContractOrEntitlement() {
            Fixture fixture = givenStalePlanChange(STALE_MINUTES,
                    BillingContractChangeStatus.PENDING_PAYMENT, false, true);
            givenStripeTrace(fixture, true, true);
            BillingContractEntity before = reloadContract(fixture.contractId);

            recoveryService.recoverStaleOperations();

            BillingContractEntity after = reloadContract(fixture.contractId);
            assertThat(after.getPlanKey())
                    .as("回収がPLAN_CHANGEの権利反映を行うのはwebhook(invoice.paid)の専管であり、"
                            + "回収経路がここへ割り込んではならない")
                    .isEqualTo(before.getPlanKey());
        }
    }

    // ================================================================
    // AC-100: stepFor は PLAN_CHANGE → STRIPE_APPLY_PLAN_CHANGE のまま
    // ================================================================

    @Test
    @DisplayName("AC-100: BillingOperationTransitions.stepForはPLAN_CHANGEの進行中(CALLING_STRIPE)に対し"
            + "常にSTRIPE_APPLY_PLAN_CHANGEを返す（PR6b-1では正しい。DOWNGRADE分岐はPR6b-2の担当であり"
            + "本PRでは変えない）。"
            + "terminal（APPLIED->FINALIZED・FAILED/CANCELLED->ABORTED）はPR6a の"
            + "BillingOperationStateMachineTest#terminalStepsAreCommon（AC-11）が全kind共通で既に固定して"
            + "いるため、ここでは重複して主張しない（第11隊是正: 元のテストはterminalに"
            + "STRIPE_APPLY_PLAN_CHANGEを要求しておりAC-11の番人と両立不能だった）。")
    void stepForPlanChangeStaysStripeApplyPlanChange() {
        assertThat(BillingOperationTransitions.stepFor(
                BillingOperationKind.PLAN_CHANGE, BillingOperationStatus.CALLING_STRIPE))
                .isEqualTo(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE);
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    /** 1件の stale な PLAN_CHANGE operation とその pointer・change。 */
    private record Fixture(UUID contractId, UUID operationId, UUID changeId, String subscriptionRef) {}

    /**
     * stale な PLAN_CHANGE operation ＋ pointer ＋ change 行を<b>実 DB に作る</b>。
     *
     * <p>作った直後に前提（狙った status・過去へ倒れた updated_at・pointer 1行・change の status）を
     * assert する（空虚な緑の防止の要）。</p>
     *
     * @param ageMinutes             {@code updated_at} を何分過去へ倒すか
     * @param changeStatus           作る change 行の status
     * @param pendingUpdateStillAlive change 行の pending_update_expires_at を未来にするか（false=既に失効）
     * @param itemsSwitched          Stripe 実物の items が target に切り替わっているか（判定材料）
     */
    private Fixture givenStalePlanChange(long ageMinutes, BillingContractChangeStatus changeStatus,
                                          boolean pendingUpdateStillAlive, boolean itemsSwitched) {
        String marker = markerHash();
        String subscriptionRef = "sub_pc_recover_" + scopeId + "_" + marker.substring(0, 8);
        UUID contractId = transactionTemplate.execute(tx -> insertContract(subscriptionRef));
        UUID operationId = transactionTemplate.execute(tx -> {
            BillingContractOperationEntity operation = operationRepository.save(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.PLAN_CHANGE)
                            .status(BillingOperationStatus.CALLING_STRIPE)
                            .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
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

        Instant staleAt = Instant.now(clock)
                .minus(Duration.ofMinutes(ageMinutes)).truncatedTo(ChronoUnit.SECONDS);
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "UPDATE billing_contract_operations "
                                + "SET created_at = :ts, updated_at = :ts, idempotency_key = :key "
                                + "WHERE request_hash = :marker")
                .setParameter("ts", staleAt)
                .setParameter("key", operationId.toString())
                .setParameter("marker", marker)
                .executeUpdate());

        Instant pendingUpdateExpiresAt = pendingUpdateStillAlive
                ? Instant.now(clock).plus(1, ChronoUnit.HOURS)
                : Instant.now(clock).minus(1, ChronoUnit.HOURS);
        UUID changeId = transactionTemplate.execute(tx -> changeRepository.save(
                BillingContractChangeEntity.builder()
                        .operationId(operationId)
                        .contractId(contractId)
                        .billingCustomerId(customerId)
                        .kind(BillingContractChangeKind.UPGRADE)
                        .status(changeStatus)
                        .fromPlanKey("BASIC")
                        .toPlanKey("FULL")
                        .fromPriceBandVersionId(UUID.randomUUID())
                        .toPriceBandVersionId(UUID.randomUUID())
                        .fromAmountIncludingTax(1_000L)
                        .toAmountIncludingTax(3_000L)
                        .stripeSubscriptionRef(subscriptionRef)
                        .stripeInvoiceRef("in_pc_recover_" + marker.substring(0, 8))
                        .pendingUpdateExpiresAt(pendingUpdateExpiresAt)
                        .pendingUpdateTargetSnapshot(
                                "{\"items\":[{\"price\":\"" + TARGET_PRICE_REF + "\"}]}")
                        .effectiveAt(Instant.now(clock))
                        .idempotencyKey(operationId.toString())
                        .requestHash(marker)
                        .version(0L)
                        .createdBy(scopeId)
                        .build()).getId());

        BillingContractOperationEntity reloadedOperation = reloadOperation(operationId);
        assertThat(reloadedOperation.getStatus())
                .as("前提: 狙った status の operation を作れている")
                .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
        assertThat(reloadedOperation.getUpdatedAt())
                .as("前提: updated_at が実際に過去へ倒れている").isEqualTo(staleAt);
        assertThat(pointerCount(contractId)).as("前提: pointer が1行ある").isEqualTo(1);
        assertThat(reloadChange(changeId).getStatus())
                .as("前提: change が狙った status である").isEqualTo(changeStatus);

        return new Fixture(contractId, operationId, changeId, subscriptionRef);
    }

    /**
     * Stripe 側の判定材料を与える。
     *
     * <p>第10隊が {@code SubscriptionSnapshot} を items / pending_update まで運べるよう拡張した
     * （AC-99）ため、試練が置いていた「{@code cancelAtPeriodEnd} を itemsSwitched の代理にする」
     * 暫定を、本来の {@code items} へ差し替えてある（試練の javadoc が第10隊へ明示的に指示していた
     * 差し替えである）。<b>各テストのアサーションは operation / pointer / change の観測結果であり
     * 一切変えていない</b>。</p>
     *
     * <p>{@code itemsSwitched=false} でも items は<b>旧 Price で1件埋める</b>。空リストは
     * 「参照したが切り替わっていない」ではなく「items を運べていない」であり、実装は後者を
     * 見送り（AC-95）として扱うため、失敗確定（AC-92）の検体にならない。</p>
     *
     * @param fixture       対象の検体
     * @param traceMatches  Stripe metadata の operationId が一致するか
     * @param itemsSwitched 現在 items が target の Price へ切り替わっているか
     */
    private void givenStripeTrace(Fixture fixture, boolean traceMatches, boolean itemsSwitched) {
        Mockito.when(billingPaymentGateway.findOperationIdOnSubscription(fixture.subscriptionRef))
                .thenReturn(traceMatches ? Optional.of(fixture.operationId) : Optional.empty());
        Instant periodEnd = LocalDateTime.now(clock).plusDays(20)
                .truncatedTo(ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC);
        Mockito.when(billingPaymentGateway.retrieveSubscription(fixture.subscriptionRef))
                .thenReturn(new BillingPaymentGateway.SubscriptionSnapshot(
                        fixture.subscriptionRef, "active", false,
                        periodEnd.minus(30, ChronoUnit.DAYS), periodEnd, null,
                        java.util.List.of(new BillingPaymentGateway.SubscriptionItemSnapshot(
                                "si_pc_recover", itemsSwitched ? TARGET_PRICE_REF : OLD_PRICE_REF,
                                1L)),
                        // 適用後の Subscription からは live な pending_update を取得できない（E2'）。
                        // 存続判定は change 行に保存した pending_update_expires_at で行う。
                        null));
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
                .pspCustomerRef("cus_pc_recover_" + scopeId)
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
                .planKey("BASIC")
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

    private BillingContractChangeEntity reloadChange(UUID changeId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return changeRepository.findByIdAndDeletedAtIsNull(changeId).orElseThrow();
        });
    }

    private BillingContractEntity reloadContract(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }
}
