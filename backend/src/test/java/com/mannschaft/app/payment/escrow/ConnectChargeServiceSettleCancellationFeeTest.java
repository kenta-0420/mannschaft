package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 募集キャンセル料の徴収（{@link ConnectChargeService#settleCancellationFee}）の試練。
 *
 * <p>設計書 {@code docs/features/F03.11.1_cancellation_fee_payment.md} §3.2〜§3.5・§7 の
 * 受け入れ条件 AC-1 / AC-2 / AC-11 / AC-12 / AC-13 / AC-16 / AC-22 / AC-23 / AC-26 / AC-30 を担う。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。徴収の入口は宣言だけが置かれており、
 * 中身は第四陣で実装される。</p>
 *
 * <p><b>設計書内の食い違いについて</b>: §11.1 の AC-22 補足は返金額を「transferAmount − キャンセル料」と
 * 書いているが、これは R-3 時点の記述であり、後の R-2 御裁可（§3.5.4・変更履歴 2026-08-13）で
 * 「支払者請求額基準 {@code R = C − F}」へ是正されている。利用者の負担を両経路で揃える（AC-30）ためには
 * {@code R = C − F} でなければ成立しないため、本テストは §3.5.4 に従う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 ConnectChargeService.settleCancellationFee 試練")
class ConnectChargeServiceSettleCancellationFeeTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private com.mannschaft.app.payment.FeePolicyResolver feePolicyResolver;
    @Mock private com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000f0311");
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;
    /** キャンセル記録 ID。冪等キーはこの値から決定論的に導出される（§7.1）。 */
    private static final String RECORD_REF = "77";

    /** 参加費（額面）。 */
    private static final long FACE_AMOUNT = 10_000L;
    /** C: 支払者の請求額（escrow.amount）。 */
    private static final long CHARGE_AMOUNT = 10_250L;
    /** A: 原取引の運営手数料。0 だと配分の誤りが表に出ないため必ず正の値を使う（§11.1 AC-30）。 */
    private static final long APPLICATION_FEE = 250L;
    /** F: キャンセル料（丸め後）。 */
    private static final long CANCELLATION_FEE = 3_000L;

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver,
                feeRecoveryBalanceRepository);
    }

    private EscrowTransactionEntity escrow(EscrowStatus status) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(LISTING_ID)
                .sourceParticipantId(PARTICIPANT_ID)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(FACE_AMOUNT).amount(CHARGE_AMOUNT).applicationFeeAmount(APPLICATION_FEE)
                .currency("JPY").status(status).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    /** 三つ組での引き当てと行ロック取得の双方に同じ escrow を返す（実装は必ず行ロック下で状態を再検査する）。 */
    private void givenEscrow(EscrowTransactionEntity e) {
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID)).willReturn(Optional.of(e));
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private SettleCancellationFeeResult settle(ConnectChargeService svc, long fee) {
        return svc.settleCancellationFee(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, fee, RECORD_REF);
    }

    // ==========================================================
    // 部分キャプチャ経路（与信のみ・§3.2）
    // ==========================================================

    @Test
    @DisplayName("AC-1: 料金>0 のキャンセルで与信からキャンセル料と同額を確定し CAPTURED_PARTIAL＋PaymentIntent ID を返す")
    void ac1_authorizedEscrow_capturesFeeAmountAndReturnsPaymentIntentReference() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.AUTHORIZED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));

        SettleCancellationFeeResult result = settle(svc, CANCELLATION_FEE);

        // キャンセル料ちょうどを、記録 ID 由来の不変な冪等キーで確定する（§7.1）。
        verify(stripePaymentProvider).captureManualPaymentIntent(
                "pi_abc", CANCELLATION_FEE, APPLICATION_FEE, "canfee-" + RECORD_REF);
        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.CAPTURED_PARTIAL);
        assertThat(result.stripeReference()).isEqualTo("pi_abc");

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.CAPTURED);
    }

    @Test
    @DisplayName("AC-2: 部分キャプチャの残額は Stripe が自動解放する（解放 API を呼ばず amount_to_capture に料金額を渡す）")
    void ac2_partialCapture_doesNotCallCancelAuthorization() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.AUTHORIZED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));

        settle(svc, CANCELLATION_FEE);

        ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
        verify(stripePaymentProvider).captureManualPaymentIntent(
                anyString(), amountCaptor.capture(), any(), anyString());
        assertThat(amountCaptor.getValue())
                .as("与信額全部ではなくキャンセル料の額だけを確定する")
                .isEqualTo(CANCELLATION_FEE);
        // 残額の解放は Stripe が自動で行うため、取消 API を呼んではならない（呼ぶと二重操作になる）。
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
    }

    @Test
    @DisplayName("AC-13: 料金が参加費とちょうど同額なら全額キャプチャで成立する（境界）")
    void ac13_feeEqualsFaceAmount_capturesEntireFaceAmount() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.AUTHORIZED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));

        SettleCancellationFeeResult result = settle(svc, FACE_AMOUNT);

        verify(stripePaymentProvider).captureManualPaymentIntent(
                "pi_abc", FACE_AMOUNT, APPLICATION_FEE, "canfee-" + RECORD_REF);
        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.CAPTURED_PARTIAL);
        assertThat(result.uncollectedAmount()).isZero();
    }

    @Test
    @DisplayName("AC-16: 徴収を2回起動しても Stripe への確定は1回のみ・2回目は NO_OP（冪等）")
    void ac16_settleTwice_capturesOnlyOnce() {
        ConnectChargeService svc = service();
        // 同一インスタンスを返すため、1回目で CAPTURED になった状態を2回目が観測する（行ロック下の状態再検査・§7.2）。
        givenEscrow(escrow(EscrowStatus.AUTHORIZED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));

        SettleCancellationFeeResult first = settle(svc, CANCELLATION_FEE);
        SettleCancellationFeeResult second = settle(svc, CANCELLATION_FEE);

        // 呼び出し回数だけでなく、結果の状態でも二重課金でないことを確かめる。
        verify(stripePaymentProvider, times(1)).captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString());
        assertThat(first.outcome()).isEqualTo(SettleCancellationFeeOutcome.CAPTURED_PARTIAL);
        assertThat(second.outcome()).isEqualTo(SettleCancellationFeeOutcome.NO_OP);
    }

    // ==========================================================
    // 差額返金経路（確定済み・§3.2・§3.5.4）
    // ==========================================================

    @Test
    @DisplayName("AC-22: 確定済みの記録をキャンセルすると 請求額−キャンセル料 が返金され Refund ID が返る")
    void ac22_capturedEscrow_refundsDifferenceAndReturnsRefundReference() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.CAPTURED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Collections.emptyList());
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_abc");
        given(stripePaymentProvider.createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_abc", "pending"));

        SettleCancellationFeeResult result = settle(svc, CANCELLATION_FEE);

        // R = C − F（支払者請求額基準・§3.5.4）。主催者手取り基準ではない。
        long expectedRefund = CHARGE_AMOUNT - CANCELLATION_FEE;
        verify(stripePaymentProvider).createConnectRefund(
                "pi_abc", expectedRefund, "cancellation", false, false,
                "canfee-refund-" + RECORD_REF);
        verify(stripePaymentProvider).reverseTransfer(
                "tr_abc", expectedRefund, "canfee-reversal-" + RECORD_REF);
        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.REFUNDED_DIFFERENCE);
        assertThat(result.stripeReference()).isEqualTo("re_abc");
    }

    @Test
    @DisplayName("AC-23(1): 返金を2回起動しても冪等キーは記録 ID 由来で不変（呼び出しごとに変わらない）")
    void ac23_refundIdempotencyKeyIsDerivedFromRecordIdOnly() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.CAPTURED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Collections.emptyList());
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_abc");
        given(stripePaymentProvider.createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_abc", "pending"));

        settle(svc, CANCELLATION_FEE);
        settle(svc, CANCELLATION_FEE);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider, org.mockito.Mockito.atLeastOnce()).createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), keyCaptor.capture());
        // 既存の "refund-{escrowId}-{seq}" は返金件数で変わるため流用してはならない（§7.1）。
        assertThat(keyCaptor.getAllValues())
                .as("すべての呼び出しが同一の記録 ID 由来キーであること")
                .containsOnly("canfee-refund-" + RECORD_REF);
    }

    @Test
    @DisplayName("AC-23(2): 1回目の返金後に2回目を通すと残額超過で拒否され、二重返金しない")
    void ac23_secondRefundRejectedByResidualCheck() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity captured = escrow(EscrowStatus.CAPTURED);
        givenEscrow(captured);
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_abc");
        given(stripePaymentProvider.createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_abc", "pending"));
        // 1回目の返金行が残っている状態を返す（残額計算の第 2 層・§7.2）。
        long alreadyRefunded = CHARGE_AMOUNT - CANCELLATION_FEE;
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID))
                .willReturn(List.of(RefundEntity.builder()
                        .escrowTransactionId(ESCROW_ID)
                        .stripeRefundId("re_abc")
                        .amount(alreadyRefunded)
                        .currency("JPY")
                        .reason("cancellation")
                        .status(RefundStatus.PENDING)
                        .build()));

        SettleCancellationFeeResult result = settle(svc, CANCELLATION_FEE);

        // 既に戻し切っているため Stripe を呼ばずに no-op で返す（500 にも二重返金にもしない）。
        verify(stripePaymentProvider, never()).createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString());
        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.NO_OP);
    }

    @Test
    @DisplayName("AC-26: キャンセル料と請求額が同額なら返金額 0 となり、返金 API を無駄に呼ばない")
    void ac26_zeroRefund_doesNotCallStripe() {
        ConnectChargeService svc = service();
        givenEscrow(escrow(EscrowStatus.CAPTURED));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Collections.emptyList());

        SettleCancellationFeeResult result = settle(svc, CHARGE_AMOUNT);

        // 既存 refund() は refundAmount <= 0 を例外にするため、呼ぶと徴収が失敗扱いになってしまう。
        verify(stripePaymentProvider, never()).createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString());
        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        // 徴収は成立している。新しい参照が無いため確定済みの元決済を指す（§3.7）。
        assertThat(result.outcome()).isNotEqualTo(SettleCancellationFeeOutcome.NOT_COLLECTIBLE);
        assertThat(result.stripeReference()).isEqualTo("pi_abc");
        assertThat(result.uncollectedAmount()).isZero();
    }

    // ==========================================================
    // 徴収不能（§6.3）
    // ==========================================================

    @Test
    @DisplayName("AC-11: 与信レコードが存在しなくても例外にせず NOT_COLLECTIBLE を返す")
    void ac11_missingEscrow_returnsNotCollectibleWithoutThrowing() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID)).willReturn(Optional.empty());

        SettleCancellationFeeResult result = settle(svc, CANCELLATION_FEE);

        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.NOT_COLLECTIBLE);
        assertThat(result.uncollectedAmount()).isEqualTo(CANCELLATION_FEE);
        verify(stripePaymentProvider, never()).captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("AC-12: 主催者の Connect 未登録（HELD）でも例外にせず NOT_COLLECTIBLE を返す")
    void ac12_heldEscrow_returnsNotCollectibleWithoutThrowing() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.HELD)));
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.HELD)));

        SettleCancellationFeeResult result = settle(svc, CANCELLATION_FEE);

        // カード上のホールドが立っていないことは異常ではなく想定内の状態である（§6.3）。
        assertThat(result.outcome()).isEqualTo(SettleCancellationFeeOutcome.NOT_COLLECTIBLE);
        assertThat(result.uncollectedAmount()).isEqualTo(CANCELLATION_FEE);
        verify(stripePaymentProvider, never()).captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString());
    }

    // ==========================================================
    // 配分（§3.5・本書で最も取り違えやすい点）
    // ==========================================================

    @Test
    @DisplayName("AC-30: どちらの徴収経路でも利用者の負担額がキャンセル料と一致する（運営手数料を上乗せしない）")
    void ac30_payerBurdenIsIdenticalAcrossBothRoutes() {
        // 運営手数料が 0 だと裁可前の誤った実装でも差が出ないため、必ず正の値で起こす（§11.1）。
        assertThat(APPLICATION_FEE).isPositive();

        // ── 経路 1: 与信のみ（部分キャプチャ）──
        ConnectChargeService captureSvc = service();
        givenEscrow(escrow(EscrowStatus.AUTHORIZED));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.captureManualPaymentIntent(
                anyString(), anyLong(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));

        settle(captureSvc, CANCELLATION_FEE);

        ArgumentCaptor<Long> captureAmount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> captureFee = ArgumentCaptor.forClass(Long.class);
        verify(stripePaymentProvider).captureManualPaymentIntent(
                anyString(), captureAmount.capture(), captureFee.capture(), anyString());
        long payerBurdenOnCaptureRoute = captureAmount.getValue();
        // 運営手数料は主催者の取り分から引く（A_eff = min(A, F)）。利用者へ上乗せしない。
        assertThat(captureFee.getValue()).isEqualTo(Math.min(APPLICATION_FEE, CANCELLATION_FEE));

        // ── 経路 2: 確定済み（差額返金）──
        ConnectChargeService refundSvc = new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver,
                feeRecoveryBalanceRepository);
        EscrowTransactionEntity capturedEscrow = escrow(EscrowStatus.CAPTURED);
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID)).willReturn(Optional.of(capturedEscrow));
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(capturedEscrow));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Collections.emptyList());
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_abc");
        given(stripePaymentProvider.createConnectRefund(
                anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_abc", "pending"));

        settle(refundSvc, CANCELLATION_FEE);

        ArgumentCaptor<Long> refundAmount = ArgumentCaptor.forClass(Long.class);
        verify(stripePaymentProvider).createConnectRefund(
                anyString(), refundAmount.capture(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
        long payerBurdenOnRefundRoute = CHARGE_AMOUNT - refundAmount.getValue();

        // 観測点は利用者の負担額であり、主催者や運営の取り分ではない。
        assertThat(payerBurdenOnCaptureRoute)
                .as("与信のみの経路での利用者負担")
                .isEqualTo(CANCELLATION_FEE);
        assertThat(payerBurdenOnRefundRoute)
                .as("確定済みの経路での利用者負担")
                .isEqualTo(CANCELLATION_FEE);
        assertThat(payerBurdenOnRefundRoute)
                .as("経路によって利用者の払う額が変わってはならない")
                .isEqualTo(payerBurdenOnCaptureRoute);
    }
}
