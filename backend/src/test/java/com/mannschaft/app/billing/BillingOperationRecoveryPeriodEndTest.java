package com.mannschaft.app.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Codex 検分 <b>P2</b>: 回収が<b>期末を解決できないまま</b> {@code APPLIED} にしないことを固定する。
 *
 * <h2>何が壊れていたのか</h2>
 * <p>停止窓(b) の回収は Stripe 実物が {@code cancel_at_period_end=true} なら
 * {@code APPLIED} へ倒して tx2 相当の反映を行う。しかし Stripe snapshot の
 * {@code currentPeriodEnd} が {@code null} のときも、そのまま {@code null} を反映していた。</p>
 *
 * <p>結果、operation は成功（{@code APPLIED}）なのに {@code billing_contracts.current_period_end}
 * と由来 entitlements の {@code valid_until} が {@code null} になる。
 * これは<b>「webhook 未達でも期末に自動失効する」という保険が消える</b>ことを意味し、
 * 解約したはずの権利が無期限に生き残る。通常経路は同じ状況を
 * 「Stripe と DB がともに null なら 409・DB を一切変更しない」（AC-37c）で塞いでいるのに、
 * 回収経路だけが素通りしていた。</p>
 *
 * <p>是正の形は通常経路と揃える。Stripe を権威とし（AC-34）、Stripe が持たなければ DB の
 * {@code current_period_end} へ fallback し、<b>どちらも無ければ検疫</b>（pointer 保持）へ倒す。
 * 「黙って成功にしない」という D8 の方針そのものである。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Codex P2: 期末を解決できない回収は APPLIED にしない")
class BillingOperationRecoveryPeriodEndTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID OPERATION_ID =
            UUID.fromString("0199abbb-bbbb-bbbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CONTRACT_ID =
            UUID.fromString("0199abcc-cccc-cccc-8ccc-cccccccccccc");
    private static final String SUB_REF = "sub_pr6a_period_end";
    /** Stripe 実物の期末（権威・AC-34）。 */
    private static final Instant STRIPE_END = Instant.parse("2026-10-01T00:00:00Z");
    /** DB 側の期末（Stripe が持たないときの fallback）。 */
    private static final LocalDateTime DB_END = LocalDateTime.of(2026, 10, 5, 0, 0);

    @Mock private BillingContractOperationRepository operationRepository;
    @Mock private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingContractOperationSagaService sagaService;
    @Mock private BillingPaymentGateway billingPaymentGateway;
    @Mock private BillingContractCancelService cancelService;
    @Mock private PlatformTransactionManager transactionManager;

    private BillingContractOperationRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new BillingContractOperationRecoveryService(
                operationRepository, pointerRepository, billingContractRepository,
                sagaService, billingPaymentGateway, FIXED_CLOCK);
        // コンストラクタ引数は試練Dの発注書で固定されているため、DB を触る協調子は
        // Spring がフィールド注入する（本番・IT）。純 UT では同じ口から差し込む。
        ReflectionTestUtils.setField(recoveryService, "transactionTemplate",
                new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(recoveryService, "cancelService", cancelService);

        given(operationRepository.findByIdAndDeletedAtIsNull(OPERATION_ID))
                .willReturn(Optional.of(staleCallingStripeOperation()));
        given(billingPaymentGateway.findOperationIdOnSubscription(SUB_REF))
                .willReturn(Optional.of(OPERATION_ID));
        given(operationRepository.compareAndSetStatus(
                any(), any(), any(), any(), any(), any()))
                .willReturn(1);
    }

    @Test
    @DisplayName("P2: Stripe も DB も期末を持たないなら APPLIED にせず検疫へ倒す（pointer は保持）")
    void quarantinesWhenPeriodEndIsUnresolvable() {
        givenStripeAppliedWithPeriodEnd(null);
        givenContractPeriodEnd(null);

        assertThat(recoveryService.recoverOperation(OPERATION_ID)).isTrue();

        verify(operationRepository).compareAndSetStatus(
                eq(OPERATION_ID), eq(BillingOperationStatus.CALLING_STRIPE),
                eq(BillingOperationStatus.RECONCILIATION_REQUIRED), any(),
                eq(BillingContractOperationRecoveryService.ERROR_PERIOD_END_UNRESOLVED), any());
    }

    @Test
    @DisplayName("P2: 期末を解決できないときは反映を一切行わない（null を DB へ書き込まない）")
    void doesNotReflectNullPeriodEnd() {
        givenStripeAppliedWithPeriodEnd(null);
        givenContractPeriodEnd(null);

        recoveryService.recoverOperation(OPERATION_ID);

        verify(cancelService, never()).applyRecoveredCancel(any(), any());
    }

    @Test
    @DisplayName("P2: 期末を解決できないときは pointer を解放しない（検疫は pointer を保持する・AC-8）")
    void keepsPointerWhenQuarantining() {
        givenStripeAppliedWithPeriodEnd(null);
        givenContractPeriodEnd(null);

        recoveryService.recoverOperation(OPERATION_ID);

        verify(pointerRepository, never()).hardDeleteByContractIdAndOperationId(any(), any());
    }

    @Test
    @DisplayName("P2: Stripe が期末を持たなくても DB にあれば fallback して APPLIED にする（取りこぼさない）")
    void fallsBackToContractPeriodEnd() {
        givenStripeAppliedWithPeriodEnd(null);
        givenContractPeriodEnd(DB_END);

        assertThat(recoveryService.recoverOperation(OPERATION_ID)).isTrue();

        verify(operationRepository).compareAndSetStatus(
                eq(OPERATION_ID), eq(BillingOperationStatus.CALLING_STRIPE),
                eq(BillingOperationStatus.APPLIED), any(), eq(null), any());
        verify(cancelService).applyRecoveredCancel(CONTRACT_ID, DB_END);
    }

    @Test
    @DisplayName("P2: Stripe が期末を持つならそれを権威として採る（AC-34。DB 値では上書きしない・陽性対照）")
    void prefersStripePeriodEnd() {
        givenStripeAppliedWithPeriodEnd(STRIPE_END);
        givenContractPeriodEnd(DB_END);

        recoveryService.recoverOperation(OPERATION_ID);

        verify(cancelService).applyRecoveredCancel(
                CONTRACT_ID, LocalDateTime.ofInstant(STRIPE_END, ZoneOffset.UTC));
        verify(cancelService, never()).applyRecoveredCancel(CONTRACT_ID, DB_END);
    }

    private void givenStripeAppliedWithPeriodEnd(Instant periodEnd) {
        given(billingPaymentGateway.retrieveSubscription(anyString()))
                .willReturn(new BillingPaymentGateway.SubscriptionSnapshot(
                        SUB_REF, "active", true, NOW.minusSeconds(86_400), periodEnd, null));
    }

    private void givenContractPeriodEnd(LocalDateTime periodEnd) {
        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(1L)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(UUID.randomUUID())
                .currentPeriodEnd(periodEnd)
                .pspSubscriptionRef(SUB_REF)
                .build();
        contract.setId(CONTRACT_ID);
        given(billingContractRepository.findByIdAndDeletedAtIsNull(CONTRACT_ID))
                .willReturn(Optional.of(contract));
    }

    private BillingContractOperationEntity staleCallingStripeOperation() {
        BillingContractOperationEntity operation = BillingContractOperationEntity.builder()
                .contractId(CONTRACT_ID)
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingOperationKind.CANCEL)
                .status(BillingOperationStatus.CALLING_STRIPE)
                .step(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION)
                .idempotencyKey(OPERATION_ID.toString())
                .requestHash("0".repeat(64))
                .stripeSubscriptionRef(SUB_REF)
                .version(0L)
                .actorKind(BillingOperationActorKind.USER)
                .createdBy(1L)
                .createdAt(NOW.minusSeconds(3_600))
                .updatedAt(NOW.minusSeconds(3_600))
                .build();
        operation.setId(OPERATION_ID);
        return operation;
    }
}
