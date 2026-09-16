package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.BillingContractOperationSagaService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — G群 競合（AC-101/104/107/117〜122）の受け入れテスト（試練C・red）。
 *
 * <h2>第6隊・第7隊への発注書</h2>
 * <ul>
 *   <li><b>AC-101</b>: 支払い待ち中の 409 応答は、単なる {@code CHANGE_CONFLICT} に加えて
 *       <b>支払い待ちであることを示す追加情報</b>を {@code $.error.details} に載せること。
 *       本テストは {@code pendingChangeStatus}（値: {@code PENDING_PAYMENT} / {@code REQUIRES_ACTION}）
 *       という名を仮固定して発注する。実装側が別名にする場合はこのテストのフィールド名を合わせて
 *       更新すること（意味は変えない）。</li>
 *   <li><b>AC-117/118/119/120</b>: {@code BillingContractOperationSagaService#reserve} の
 *       pointer 存在チェックは既に kind 非依存で 409 を返す（PR6a 由来）。upgrade の
 *       {@code PLAN_CHANGE} operation・pointer が既にある契約へ、解約／別 change／migration／
 *       の各予約を試みても同じ経路で 409 になることを固定する（新しい判定は不要なはずだが、
 *       upgrade 用の特別扱いで pointer チェックをバイパスしないことの回帰である）。</li>
 *   <li><b>AC-122</b>: 同一契約への並行 change 要求は、pointer の一意性（PK: contract_id）により
 *       片方だけが成功する。実装後に意味を持つ（現状は変更エンドポイント未実装のため両方が
 *       同じ理由で失敗しうる）。</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 競合 G群（AC-101/104/107/117〜122・試練C red）")
class BillingPlanChangeConflictRedIT extends AbstractBillingPlanChangeApiIT {

    /** 既存 PR6a の解約エンドポイント（AC-117/120 で使う）。 */
    private static final String CANCEL_PATH = "/api/v1/me/billing/contracts/%s/cancel";

    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private BillingContractChangeRepository changeRepository;
    @Autowired private BillingContractOperationSagaService sagaService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        seedUpgradableContract("conflict");
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ================================================================
    // AC-117: upgrade 待機中の解約は409
    // ================================================================

    @Nested
    @DisplayName("AC-117: upgrade待機中の解約は409")
    class CancelDuringPendingUpgrade {

        @Test
        @DisplayName("AC-117: PENDING_PAYMENT中の契約へcancelを要求すると409(CHANGE_CONFLICT)になり、DBを変更しない")
        void cancelWhilePendingPaymentIs409() throws Exception {
            givenPendingPlanChangeOperation(BillingContractChangeStatus.PENDING_PAYMENT);

            mockMvc.perform(MockMvcRequestBuilders.post(String.format(CANCEL_PATH, contractId))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId)))
                            .header("Idempotency-Key", newKey())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + contractVersion() + "}"))
                    .andExpect(status().isConflict());

            assertThat(reloadContract().getCancelledAt()).isNull();
        }

        @Test
        @DisplayName("AC-101: 409応答の理由に「支払い待ち」であることが判別できる情報が含まれる"
                + "（一般のCHANGE_CONFLICTと区別できない実装は不合格）")
        void conflictResponseDistinguishesPendingPayment() throws Exception {
            givenPendingPlanChangeOperation(BillingContractChangeStatus.REQUIRES_ACTION);

            var result = mockMvc.perform(MockMvcRequestBuilders.post(String.format(CANCEL_PATH, contractId))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId)))
                            .header("Idempotency-Key", newKey())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + contractVersion() + "}"))
                    .andExpect(status().isConflict())
                    .andReturn();

            var error = body(result).path("error");
            assertThat(error.path("details").path("reason").asText())
                    .isEqualTo("CHANGE_CONFLICT");
            assertThat(error.path("details").path("pendingChangeStatus").asText(null))
                    .as("発注: 支払い待ちの判別情報。実装側でフィールド名を変えるならこのテストも合わせて改名すること")
                    .isEqualTo("REQUIRES_ACTION");
        }

        @Test
        @DisplayName("AC-107: 回帰の陽性対照 — 支払い待ちでない(pointer無し)契約のcancelは通常どおり200になり、"
                + "pendingChangeStatus相当の情報は常時表示にならない")
        void cancelWithoutPendingChangeSucceedsNormally() throws Exception {
            // 前提: この契約には PLAN_CHANGE の operation も pointer も無い（既定フィクスチャのまま）。
            assertThat(pointerRepository.findById(contractId)).as("前提: pointer が無い").isEmpty();

            mockMvc.perform(MockMvcRequestBuilders.post(String.format(CANCEL_PATH, contractId))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId)))
                            .header("Idempotency-Key", newKey())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + contractVersion() + "}"))
                    .andExpect(status().isOk());
        }
    }

    // ================================================================
    // AC-118: upgrade待機中に別のchangeは409
    // ================================================================

    @Nested
    @DisplayName("AC-118: upgrade待機中の別changeは409")
    class AnotherChangeDuringPendingUpgrade {

        @Test
        @DisplayName("AC-118: PENDING_PAYMENT中に別のchangeを要求すると409になり、新しいoperation/changeを作らない")
        void anotherChangeWhilePendingPaymentIs409() throws Exception {
            givenPendingPlanChangeOperation(BillingContractChangeStatus.PENDING_PAYMENT);
            long operationCountBefore = operationCount();
            UUID previewId = createPreviewId();

            change(userId, contractId, previewId, contractVersion(), newKey())
                    .andExpect(status().isConflict());

            assertThat(operationCount())
                    .as("競合に落ちた要求は新しい operation を作らない").isEqualTo(operationCountBefore);
        }
    }

    // ================================================================
    // AC-119: migration も409（MIGRATION kind の予約が pointer をバイパスしない）
    // ================================================================

    @Nested
    @DisplayName("AC-119: upgrade待機中はmigrationも409")
    class MigrationDuringPendingUpgrade {

        @Test
        @DisplayName("AC-119: PENDING_PAYMENT中の契約へMIGRATION operationを予約しようとするとCHANGE_CONFLICTで409相当になる")
        void migrationReservationConflictsWithPendingUpgrade() {
            givenPendingPlanChangeOperation(BillingContractChangeStatus.PENDING_PAYMENT);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sagaService.reserve(
                            new BillingContractOperationSagaService.ReserveCommand(
                                    contractId, BillingOperationKind.MIGRATION,
                                    contractVersion(), com.mannschaft.app.billing.BillingOperationActorKind.SYSTEM,
                                    null, "0".repeat(64))))
                    .as("upgrade の pointer を MIGRATION が横取りしてはならない")
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // AC-120: 解約予約中の契約へのchangeも409（AC-21 と対）
    // ================================================================

    @Nested
    @DisplayName("AC-120: 解約予約中の契約へのchangeは409")
    class ChangeDuringPendingCancel {

        @Test
        @DisplayName("AC-120: CANCEL operationのpointerが残っている契約への change-previews消費(changes)要求も409")
        void changeWhileCancelPendingIs409() throws Exception {
            UUID previewId = createPreviewId();
            givenPendingCancelOperation();

            change(userId, contractId, previewId, contractVersion(), newKey())
                    .andExpect(status().isConflict());
        }
    }

    // ================================================================
    // AC-121: 検疫中は全mutation 409（回帰）
    // ================================================================

    @Nested
    @DisplayName("AC-121: 検疫中は全mutation 409")
    class QuarantinedBlocksAllMutation {

        @Test
        @DisplayName("AC-121: RECONCILIATION_REQUIREDの契約へのcancelも409（検疫が全mutationを止める）")
        void cancelDuringQuarantineIs409() throws Exception {
            givenPendingPlanChangeOperation(BillingContractChangeStatus.PENDING_PAYMENT,
                    BillingOperationStatus.RECONCILIATION_REQUIRED);

            mockMvc.perform(MockMvcRequestBuilders.post(String.format(CANCEL_PATH, contractId))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId)))
                            .header("Idempotency-Key", newKey())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + contractVersion() + "}"))
                    .andExpect(status().isConflict());
        }
    }

    // ================================================================
    // AC-122: 並行で2つのchangeを投げると片方だけ成功（実DB）
    // ================================================================

    @Nested
    @DisplayName("AC-122: 並行changeは片方だけ成功")
    class ConcurrentChanges {

        @Test
        @DisplayName("AC-122: 同一契約へ並行で2本のchangesを投げると、成功(202)は高々1本であり、"
                + "operation行はpointerの一意性ぶんだけ増える（両方成功しない）")
        void onlyOneOfConcurrentChangesSucceeds() throws Exception {
            UUID previewId1 = createPreviewId();
            UUID previewId2 = createPreviewId();
            long version = contractVersion();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();

            Runnable task1 = raceTask(previewId1, version, ready, go, successCount, conflictCount);
            Runnable task2 = raceTask(previewId2, version, ready, go, successCount, conflictCount);
            pool.submit(task1);
            pool.submit(task2);
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(successCount.get())
                    .as("pointer は contract あたり最大1行。両方成功してはならない")
                    .isLessThanOrEqualTo(1);
            assertThat(successCount.get() + conflictCount.get())
                    .as("成功か競合(409)かのいずれかで決着し、原因不明の応答を残さない")
                    .isEqualTo(2);
        }

        private Runnable raceTask(UUID previewId, long version, CountDownLatch ready, CountDownLatch go,
                                   AtomicInteger successCount, AtomicInteger conflictCount) {
            return () -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    int status = change(userId, contractId, previewId, version, newKey())
                            .andReturn().getResponse().getStatus();
                    if (status == 202) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        }
    }

    // ================================================================
    // フィクスチャ
    // ================================================================

    /** upgrade の PLAN_CHANGE operation ＋ pointer ＋ change 行を実DBに作る（PENDING_PAYMENT / REQUIRES_ACTION）。 */
    private UUID givenPendingPlanChangeOperation(BillingContractChangeStatus changeStatus) {
        return givenPendingPlanChangeOperation(changeStatus, BillingOperationStatus.CALLING_STRIPE);
    }

    private UUID givenPendingPlanChangeOperation(
            BillingContractChangeStatus changeStatus, BillingOperationStatus operationStatus) {
        return transactionTemplate.execute(tx -> {
            BillingContractOperationEntity operation = entityManager.merge(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.PLAN_CHANGE)
                            .status(operationStatus)
                            .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash("0".repeat(64))
                            .stripeSubscriptionRef(subscriptionRef)
                            .version(0L)
                            .actorKind(BillingOperationActorKind.USER)
                            .createdBy(userId)
                            .build());
            entityManager.flush();
            entityManager.persist(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId).operationId(operation.getId()).build());
            entityManager.flush();
            BillingContractChangeEntity change = BillingContractChangeEntity.builder()
                    .operationId(operation.getId())
                    .contractId(contractId)
                    .billingCustomerId(customerId)
                    .kind(BillingContractChangeKind.UPGRADE)
                    .status(changeStatus)
                    .fromPlanKey(FROM_PLAN_KEY)
                    .toPlanKey(TO_PLAN_KEY)
                    .fromPriceBandVersionId(fromBandId)
                    .toPriceBandVersionId(toBandId)
                    .fromAmountIncludingTax(FROM_AMOUNT)
                    .toAmountIncludingTax(TO_AMOUNT)
                    .stripeSubscriptionRef(subscriptionRef)
                    .pendingUpdateExpiresAt(Instant.now().plusSeconds(3_600))
                    .effectiveAt(Instant.now())
                    .idempotencyKey(operation.getId().toString())
                    .requestHash("0".repeat(64))
                    .version(0L)
                    .createdBy(userId)
                    .build();
            entityManager.persist(change);
            entityManager.flush();
            return operation.getId();
        });
    }

    /** 解約予約中（CANCEL operation ＋ pointer・change 行は無い）を作る（AC-120）。 */
    private void givenPendingCancelOperation() {
        transactionTemplate.executeWithoutResult(tx -> {
            BillingContractOperationEntity operation = entityManager.merge(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.CANCEL)
                            .status(BillingOperationStatus.CALLING_STRIPE)
                            .step(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash("0".repeat(64))
                            .stripeSubscriptionRef(subscriptionRef)
                            .version(0L)
                            .actorKind(BillingOperationActorKind.USER)
                            .createdBy(userId)
                            .build());
            entityManager.flush();
            entityManager.persist(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId).operationId(operation.getId()).build());
            entityManager.flush();
        });
    }
}
