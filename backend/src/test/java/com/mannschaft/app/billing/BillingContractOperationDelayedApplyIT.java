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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codex 再検分 <b>P1</b>: <b>遅れて届いた tx2 が、後から成立した利用者操作を上書きしない</b>ことを
 * 実 MySQL の<b>成果物</b>で測る。
 *
 * <h2>何が壊れていたのか（利用者から見た症状）</h2>
 * <p>先着競合の収束（Codex 検分 P1-2）を入れた際、結末の確認を {@code reflection} の<b>後ろ</b>に
 * 置いていた。収束する場合でも反映だけは実行して commit されるため、次の順で事故が起きる。</p>
 * <ol>
 *   <li>利用者が解約。Stripe 呼び出しは成功。</li>
 *   <li>{@code customer.subscription.updated} が先に届き、回収が operation を {@code APPLIED} 化。</li>
 *   <li>利用者が<b>撤回</b>。{@code cancelled_at} は NULL、{@code valid_until} も NULL（無期限）へ戻る。</li>
 *   <li>遅れていた元の解約の tx2 が走り、<b>古い反映が再適用されて撤回を消す</b>。</li>
 * </ol>
 * <p>利用者から見ると「撤回したのに解約されたまま」である。収束を入れる前は、後続の自己遷移
 * エラーがこの再適用を<b>偶然</b>巻き戻していた（保険が効いていた）。収束させた時点でその保険が
 * 外れたため、順序そのものを正す必要がある。</p>
 *
 * <h2>なぜ呼び出し回数ではなく成果物で測るのか</h2>
 * <p>「反映が再実行されないこと」をモックの呼び出し有無で測ると、順序が逆でも
 * 「結果的に呼ばれない」形の実装で緑になり得る。ここで確かめたいのは<b>DB に残った姿</b>——
 * 契約の {@code cancelled_at} と由来 entitlement の {@code valid_until} が
 * <b>撤回後の姿のまま</b>であること——なので、実 DB でしか意味を成さない。</p>
 *
 * <p><b>{@code @Transactional} を付けないのは意図である</b>。tx2 は独立したトランザクションとして
 * commit されなければ、上書きが起きたか否かを別接続から観測できない。後片付けは
 * {@link #tearDown()} が行う（試練A・試練D の IT と同じ流儀）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Codex 再検分 P1: 遅れた tx2 は後続の利用者操作を上書きしない（実MySQL）")
class BillingContractOperationDelayedApplyIT extends AbstractMySqlIntegrationTest {

    private static final String FEATURE_KEY = "F20_1_DELAYED_APPLY";

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractCancelService cancelService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    private Long scopeId;
    private UUID customerId;
    private UUID contractId;
    private LocalDateTime periodEnd;

    @BeforeEach
    void setUp() {
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 790_000_000L;
        periodEnd = LocalDateTime.now(clock).plusDays(20).truncatedTo(ChronoUnit.SECONDS);
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            contractId = insertResumedContract();
            insertEntitlement();
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
            entityManager.createNativeQuery(
                            "DELETE FROM entitlements WHERE scope_id = :s AND feature_key = :f")
                    .setParameter("s", scopeId).setParameter("f", FEATURE_KEY).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
        });
    }

    // ================================================================
    // 退行そのもの: 回収済み CANCEL の後に RESUME が成立している状態
    // ================================================================

    @Test
    @DisplayName("P1: 回収が APPLIED 化した後に撤回が成立していれば、遅れた解約の tx2 は契約を上書きしない")
    void delayedApplyDoesNotOverwriteLaterResumeOnContract() {
        UUID operationId = givenRecoveredCancelOperation();

        whenDelayedCancelTx2Arrives(operationId);

        assertThat(reloadContract().getCancelledAt())
                .as("撤回したのに解約されたまま、という姿にしてはならない")
                .isNull();
    }

    @Test
    @DisplayName("P1: 同じく由来 entitlement の valid_until も撤回後の姿（無期限）のまま残る")
    void delayedApplyDoesNotOverwriteLaterResumeOnEntitlement() {
        UUID operationId = givenRecoveredCancelOperation();

        whenDelayedCancelTx2Arrives(operationId);

        assertThat(reloadEntitlementValidUntil())
                .as("古い解約の反映が再適用されると、撤回したはずの権利に期限が戻る")
                .isNull();
    }

    @Test
    @DisplayName("P1: 遅れた tx2 は成功として収束し、いま DB にある姿（撤回済み）を返す")
    void delayedApplyConvergesAndReturnsCurrentTruth() {
        UUID operationId = givenRecoveredCancelOperation();

        BillingContractCancelService.CancelView view = whenDelayedCancelTx2Arrives(operationId);

        assertThat(view.scheduledAt()).as("返すのは『自分が書いたはずの姿』ではなく、いまの真実").isNull();
        assertThat(view.cancelScheduled()).isFalse();
    }

    @Test
    @DisplayName("P1: 遅れた tx2 は operation を APPLIED のまま据え置き、pointer も増やさない")
    void delayedApplyLeavesOperationAndPointerUntouched() {
        UUID operationId = givenRecoveredCancelOperation();

        whenDelayedCancelTx2Arrives(operationId);

        assertThat(reloadOperation(operationId).getStatus())
                .isEqualTo(BillingOperationStatus.APPLIED);
        assertThat(pointerCount()).isZero();
    }

    // ================================================================
    // 陽性対照: 通常経路が先着したときは、従来どおり反映される
    // ================================================================

    @Test
    @DisplayName("P1: 通常経路が先着（CALLING_STRIPE）なら反映は実行され APPLIED へ進む"
            + "（陽性対照。収束を口実に『常に反映しない』実装ではない）")
    void normalPathStillApplies() {
        UUID operationId = givenInFlightCancelOperation();

        BillingContractCancelService.CancelView view = whenDelayedCancelTx2Arrives(operationId);

        assertThat(reloadContract().getCancelledAt())
                .as("自分が先着したのだから、反映は実行されなければならない").isNotNull();
        assertThat(reloadEntitlementValidUntil()).isEqualTo(periodEnd);
        assertThat(reloadOperation(operationId).getStatus())
                .isEqualTo(BillingOperationStatus.APPLIED);
        assertThat(pointerCount()).as("terminal 確定と同一トランザクションで解放される（AC-7）").isZero();
        assertThat(view.scheduledAt()).isNotNull();
    }

    // ================================================================
    // 実行 / フィクスチャ
    // ================================================================

    /**
     * 遅れて届いた解約の tx2。呼び出し元（{@code BillingContractCancelService#scheduleCancel}）と
     * <b>同じ形</b>で呼ぶ——反映は正本の {@code applyCancel}、収束時は現在の姿の読み取り。
     */
    private BillingContractCancelService.CancelView whenDelayedCancelTx2Arrives(UUID operationId) {
        return sagaService.applyAndFinalize(operationId,
                () -> cancelService.applyRecoveredCancel(contractId, periodEnd),
                () -> cancelService.viewOf(contractId));
    }

    /** 回収が先着して APPLIED 化し、その後に撤回が成立した状態（pointer は解放済み）。 */
    private UUID givenRecoveredCancelOperation() {
        UUID operationId = insertOperation(BillingOperationStatus.APPLIED,
                BillingOperationStep.FINALIZED, false);
        assertThat(reloadContract().getCancelledAt())
                .as("前提: 撤回後の姿（解約予約なし）で始まる").isNull();
        assertThat(reloadEntitlementValidUntil()).as("前提: 権利は無期限に戻っている").isNull();
        return operationId;
    }

    /** 通常経路が進行中（Stripe 呼び出し済み・tx2 未了）で pointer を握っている状態。 */
    private UUID givenInFlightCancelOperation() {
        UUID operationId = insertOperation(BillingOperationStatus.CALLING_STRIPE,
                BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION, true);
        assertThat(pointerCount()).as("前提: 進行中なので pointer がある").isEqualTo(1);
        return operationId;
    }

    private UUID insertOperation(
            BillingOperationStatus status, BillingOperationStep step, boolean withPointer) {
        return transactionTemplate.execute(tx -> {
            BillingContractOperationEntity operation = operationRepository.save(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.CANCEL)
                            .status(status)
                            .step(step)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash("d".repeat(64))
                            .stripeSubscriptionRef("sub_delayed_" + scopeId)
                            .version(0L)
                            .actorKind(BillingOperationActorKind.USER)
                            .createdBy(scopeId)
                            .build());
            entityManager.flush();
            if (withPointer) {
                pointerRepository.save(ActiveBillingContractOperationPointerEntity.builder()
                        .contractId(contractId)
                        .operationId(operation.getId())
                        .build());
                entityManager.flush();
            }
            return operation.getId();
        });
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_delayed_" + scopeId)
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

    /** 撤回が成立した後の姿（{@code cancelled_at} が NULL）。 */
    private UUID insertResumedContract() {
        return billingContractRepository.save(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(customerId)
                .contractedAt(LocalDateTime.now(clock).minusDays(10))
                .currentPeriodEnd(periodEnd)
                .cancelledAt(null)
                .createdBy(scopeId)
                .payerUserId(scopeId)
                .pspSubscriptionRef("sub_delayed_" + scopeId)
                .build()).getId();
    }

    /** 撤回により無期限（{@code valid_until} が NULL）へ戻った由来 entitlement。 */
    private void insertEntitlement() {
        EntitlementEntity entitlement = EntitlementEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM).scopeId(scopeId)
                .featureKey(FEATURE_KEY)
                .sourceKind(EntitlementSourceKind.PLAN).sourceRefId(contractId)
                .validFrom(LocalDateTime.now(clock).minusMonths(1))
                .validUntil(null)
                .build();
        entityManager.persist(entitlement);
        entityManager.flush();
    }

    // ================================================================
    // DB 実読ヘルパ（第一次キャッシュを通さない）
    // ================================================================

    private BillingContractEntity reloadContract() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }

    private LocalDateTime reloadEntitlementValidUntil() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            List<EntitlementEntity> rows = entitlementRepository
                    .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                            EntitlementSourceKind.PLAN, contractId);
            assertThat(rows).as("前提: 由来 entitlement が1件ある").hasSize(1);
            return rows.get(0).getValidUntil();
        });
    }

    private BillingContractOperationEntity reloadOperation(UUID operationId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId).orElseThrow();
        });
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
}
