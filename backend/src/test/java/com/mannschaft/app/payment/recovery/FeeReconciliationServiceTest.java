package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.escrow.LedgerAccount;
import com.mannschaft.app.payment.escrow.LedgerDirection;
import com.mannschaft.app.payment.escrow.LedgerEntryEntity;
import com.mannschaft.app.payment.escrow.LedgerEntryRepository;
import com.mannschaft.app.payment.escrow.LedgerEntryType;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 謝礼決済 第三陣 C2: {@link FeeReconciliationService} 単体テスト（test-first・Stripe は mock）。
 *
 * <p>検証観点（設計書 02 §6.3）:</p>
 * <ol>
 *   <li>(a) pending だった ModeB 返金が balance_transaction 確定後に C1 と同一会計で補完計上される。</li>
 *   <li>(a) 依然 pending なら補完せず STILL_PENDING（滞留＝アラート対象）を返す。</li>
 *   <li>(b) 複式不一致を検出し false（log.error 上申）を返す／一致なら true。</li>
 *   <li>(c) 純益が application_fee − Stripe 実手数料で正しく、実手数料が ledger(FEE) に実額記帳される。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeReconciliationService 単体テスト（pending補完／複式検算／純益突合・§6.3）")
class FeeReconciliationServiceTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");

    private FeeReconciliationService service() {
        return new FeeReconciliationService(
                escrowTransactionRepository, ledgerEntryRepository,
                feeRecoveryBalanceRepository, stripePaymentProvider);
    }

    private EscrowTransactionEntity escrow(EscrowStatus status) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .organizationId(5L)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    private LedgerEntryEntity entry(LedgerEntryType type, LedgerAccount account, LedgerDirection dir, long amount) {
        return LedgerEntryEntity.builder()
                .escrowTransactionId(ESCROW_ID).entryType(type).account(account).direction(dir)
                .amount(amount).currency("JPY").runningBalance(0L).build();
    }

    // ============================================================
    // (a) pending 補完
    // ============================================================

    @Test
    @DisplayName("(a) pending だった ModeB 返金: balance_transaction 確定後に C1 と同一会計（RECOVERY 仕訳＋outstanding 計上）で補完計上され COMPLETED")
    void completePendingRecovery_confirmedAfterPending_recordsRecovery() {
        FeeReconciliationService svc = service();
        EscrowTransactionEntity captured = escrow(EscrowStatus.REFUNDED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(captured));
        // balance_transaction が確定し実手数料 369（minor）が取れた。
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        // 全額 ModeB 返金: D PAYER REFUND = grossRefund = chargeAmount(10,250)。Refund ID 単位の grossRefund を記帳から復元。
        given(ledgerEntryRepository.findModeBGrossRefundByRefundId(ESCROW_ID))
                .willReturn(List.<Object[]>of(new Object[]{"re_1", 10_250L}));
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        FeeReconciliationService.CompletionOutcome outcome = svc.completePendingRecovery(ESCROW_ID);

        assertThat(outcome).isEqualTo(FeeReconciliationService.CompletionOutcome.COMPLETED);

        // RECOVERY 仕訳（D PLATFORM_FEE=C PAYEE=369・全額返金なので比例計上=stripeFee 全額）が追記される。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> recovery = ledgerCaptor.getValue();
        assertThat(recovery).hasSize(2);
        assertThat(recovery).allMatch(e -> e.getEntryType() == LedgerEntryType.RECOVERY && e.getAmount() == 369L);
        assertThat(recovery).anyMatch(e -> e.getDirection() == LedgerDirection.D && e.getAccount() == LedgerAccount.PLATFORM_FEE);
        assertThat(recovery).anyMatch(e -> e.getDirection() == LedgerDirection.C && e.getAccount() == LedgerAccount.PAYEE);

        // outstanding_amount に同額 369 が積まれる。
        ArgumentCaptor<FeeRecoveryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balanceCaptor.capture());
        assertThat(balanceCaptor.getValue().getOutstandingAmount()).isEqualTo(369L);
        assertThat(balanceCaptor.getValue().getCurrency()).isEqualTo("jpy");
        assertThat(balanceCaptor.getValue().getConnectAccountId()).isEqualTo(PAYEE_ACCOUNT_ID);
    }

    @Test
    @DisplayName("(a) 部分 ModeB 返金: grossRefund 比率で比例計上され outstanding に積まれる（round(369×5,125/10,250)=185）")
    void completePendingRecovery_partialRefund_recordsProportionally() {
        FeeReconciliationService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.PARTIALLY_REFUNDED)));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        // 半額 ModeB 返金: D PAYER REFUND = 5,125。比例計上 = round(369 × 5,125 / 10,250) = round(184.5) = 185（HALF_UP）。
        given(ledgerEntryRepository.findModeBGrossRefundByRefundId(ESCROW_ID))
                .willReturn(List.<Object[]>of(new Object[]{"re_1", 5_125L}));
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        FeeReconciliationService.CompletionOutcome outcome = svc.completePendingRecovery(ESCROW_ID);

        assertThat(outcome).isEqualTo(FeeReconciliationService.CompletionOutcome.COMPLETED);
        ArgumentCaptor<FeeRecoveryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balanceCaptor.capture());
        assertThat(balanceCaptor.getValue().getOutstandingAmount()).isEqualTo(185L);
    }

    @Test
    @DisplayName("(a) 依然 pending: 実手数料が PROCESSING_FEE_PENDING のまま → 補完せず STILL_PENDING（滞留＝アラート対象）。RECOVERY/残高は書かない")
    void completePendingRecovery_stillPending_returnsStillPending() {
        FeeReconciliationService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.REFUNDED)));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc"))
                .willReturn(StripePaymentProvider.PROCESSING_FEE_PENDING);

        FeeReconciliationService.CompletionOutcome outcome = svc.completePendingRecovery(ESCROW_ID);

        assertThat(outcome).isEqualTo(FeeReconciliationService.CompletionOutcome.STILL_PENDING);
        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(feeRecoveryBalanceRepository, never()).save(any());
    }

    // ============================================================
    // (b) 複式検算
    // ============================================================

    @Test
    @DisplayName("(b) 複式一致: 借方合計=貸方合計 → true（検出なし）")
    void verifyDoubleEntry_balanced_returnsTrue() {
        FeeReconciliationService svc = service();
        given(ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(ESCROW_ID))
                .willReturn(List.of(
                        entry(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, LedgerDirection.D, 10_250L),
                        entry(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, LedgerDirection.C, 9_750L),
                        entry(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, LedgerDirection.C, 500L)));

        assertThat(svc.verifyDoubleEntry(ESCROW_ID)).isTrue();
    }

    @Test
    @DisplayName("(b) 複式不一致: 借方≠貸方 → false（log.error 上申・握りつぶさない）")
    void verifyDoubleEntry_imbalanced_returnsFalse() {
        FeeReconciliationService svc = service();
        given(ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(ESCROW_ID))
                .willReturn(List.of(
                        entry(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, LedgerDirection.D, 10_250L),
                        entry(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, LedgerDirection.C, 9_750L)));
        // 借方 10,250 ≠ 貸方 9,750 → 不一致。

        assertThat(svc.verifyDoubleEntry(ESCROW_ID)).isFalse();
    }

    @Test
    @DisplayName("(b) 記帳なし: 空 → true（検算対象外）")
    void verifyDoubleEntry_empty_returnsTrue() {
        FeeReconciliationService svc = service();
        given(ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(ESCROW_ID))
                .willReturn(List.of());

        assertThat(svc.verifyDoubleEntry(ESCROW_ID)).isTrue();
    }

    // ============================================================
    // (c) 純益突合
    // ============================================================

    @Test
    @DisplayName("(c) 純益突合: 純益 = application_fee(500) − Stripe実手数料(369) = 131。実手数料を ledger(FEE) に実額記帳")
    void reconcileNetProfit_computesAndRecordsFee() {
        FeeReconciliationService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        // 既存 FEE 実額記帳なし（初回突合）。
        given(ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(ESCROW_ID)).willReturn(List.of());
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        FeeReconciliationService.NetProfitOutcome outcome = svc.reconcileNetProfit(ESCROW_ID);

        assertThat(outcome.applicable()).isTrue();
        assertThat(outcome.pending()).isFalse();
        assertThat(outcome.applicationFee()).isEqualTo(500L);
        assertThat(outcome.stripeFee()).isEqualTo(369L);
        assertThat(outcome.netProfit()).isEqualTo(131L);
        assertThat(outcome.feeRecorded()).isTrue();

        // 実手数料 369 が FEE 自己完結仕訳（D PLATFORM_FEE=C PAYEE=369）で台帳化される。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> fee = ledgerCaptor.getValue();
        assertThat(fee).hasSize(2);
        assertThat(fee).allMatch(e -> e.getEntryType() == LedgerEntryType.FEE && e.getAmount() == 369L);
        assertThat(fee).anyMatch(e -> e.getDirection() == LedgerDirection.D && e.getAccount() == LedgerAccount.PLATFORM_FEE);
    }

    @Test
    @DisplayName("(c) FEE 実額が既存記帳済み: 再突合で FEE を二重記帳しない（冪等）。純益は同値で返る")
    void reconcileNetProfit_idempotentFee_skipsSecondRecording() {
        FeeReconciliationService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        // 既に FEE 実額記帳済み（stripe_object_id=pi_abc の FEE/D/PLATFORM_FEE）。
        LedgerEntryEntity existing = LedgerEntryEntity.builder()
                .escrowTransactionId(ESCROW_ID).entryType(LedgerEntryType.FEE).account(LedgerAccount.PLATFORM_FEE)
                .direction(LedgerDirection.D).amount(369L).currency("JPY").runningBalance(0L)
                .stripeObjectId("pi_abc").build();
        given(ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(ESCROW_ID)).willReturn(List.of(existing));

        FeeReconciliationService.NetProfitOutcome outcome = svc.reconcileNetProfit(ESCROW_ID);

        assertThat(outcome.netProfit()).isEqualTo(131L);
        assertThat(outcome.feeRecorded()).isFalse();
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("(c) 実手数料 pending: 純益確定できず NetProfitOutcome.pending（後続再試行）。FEE 記帳しない")
    void reconcileNetProfit_pending_returnsPending() {
        FeeReconciliationService svc = service();
        lenient().when(escrowTransactionRepository.findById(ESCROW_ID)).thenReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc"))
                .willReturn(StripePaymentProvider.PROCESSING_FEE_PENDING);

        FeeReconciliationService.NetProfitOutcome outcome = svc.reconcileNetProfit(ESCROW_ID);

        assertThat(outcome.pending()).isTrue();
        assertThat(outcome.applicable()).isFalse();
        verify(ledgerEntryRepository, never()).saveAll(any());
    }
}
