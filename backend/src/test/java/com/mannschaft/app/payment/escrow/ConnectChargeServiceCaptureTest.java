package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-c 第一波: {@link ConnectChargeService#capture}（謝礼の払出＝capture+transfer）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。
 * 検証: AUTHORIZED→CAPTURED 遷移・captured_at・複式記帳の借貸一致（capture総額=transfer+fee）/
 * 冪等（CAPTURED 済み再 capture は no-op）/ 不正状態（HELD/CANCELLED）からの capture 拒否。</p>
 *
 * <p>返金（reverse_transfer）は次波（P2-c-2）であり本テストの範囲外。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService.capture 単体テスト（払出）")
class ConnectChargeServiceCaptureTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private com.mannschaft.app.payment.FeePolicyResolver feePolicyResolver;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-000000000099");

    private ConnectChargeService service() {
        // capture は escrow 保存値ベース（policy 解決を伴わない）ため resolver は未使用（モックのみ渡す）。
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new com.mannschaft.app.payment.connect.PayeeScopeResolver(), feePolicyResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository.class));
    }

    private EscrowTransactionEntity escrow(EscrowStatus status) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    @Test
    @DisplayName("AUTHORIZED→capture: captureManualPaymentIntent 呼び CAPTURED/captured_at・ledger 借貸一致(10250=9750+500)")
    void authorized_capturesAndLedgerBalances() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(authorized));
        given(stripePaymentProvider.captureManualPaymentIntent("pi_abc", "capture-" + ESCROW_ID))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", null, "succeeded"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.capture(ESCROW_ID);

        // Stripe capture が冪等キー付きで呼ばれる。
        verify(stripePaymentProvider).captureManualPaymentIntent("pi_abc", "capture-" + ESCROW_ID);

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.CAPTURED);
        assertThat(escrowCaptor.getValue().getCapturedAt()).isNotNull();

        // 複式記帳: CAPTURE(D ESCROW 10250) / TRANSFER_OUT(C PAYEE 9750) / FEE(C PLATFORM_FEE 500)。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> entries = ledgerCaptor.getValue();
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(10_250L);

        long transferOut = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.TRANSFER_OUT)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long fee = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.FEE)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(transferOut).isEqualTo(9_750L);
        assertThat(fee).isEqualTo(500L);
        // 全行が当該 escrow に紐づく。
        assertThat(entries).allMatch(e -> ESCROW_ID.equals(e.getEscrowTransactionId()));
    }

    @Test
    @DisplayName("冪等: CAPTURED 済み再 capture→no-op（Stripe capture も ledger も呼ばない）")
    void alreadyCaptured_noOp() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));

        svc.capture(ESCROW_ID);

        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("第一陣根治: PENDING_CONFIRMATION（札主未 confirm）から capture→AUTHORIZATION_NOT_CONFIRMED(409)・Stripe never")
    void pendingConfirmation_rejected() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.PENDING_CONFIRMATION)));

        assertThatThrownBy(() -> svc.capture(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.AUTHORIZATION_NOT_CONFIRMED);

        // Stripe capture は呼ばない（真の与信が立つ前の capture を Stripe へ到達させない）。
        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("不正状態: HELD から capture→INVALID_ESCROW_STATE（払出不能・Stripe never）")
    void held_rejected() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.HELD)));

        assertThatThrownBy(() -> svc.capture(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);

        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("不正状態: CANCELLED から capture→INVALID_ESCROW_STATE（払出不能・Stripe never）")
    void cancelled_rejected() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CANCELLED)));

        assertThatThrownBy(() -> svc.capture(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);

        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
    }

    @Test
    @DisplayName("escrow 不在→404秘匿（PAYMENT_C002）・Stripe never")
    void missingEscrow_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.capture(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
    }

    @Test
    @DisplayName("AUTHORIZED だが PI 未設定（異常）→INVALID_ESCROW_STATE（Stripe never）")
    void authorizedWithoutPi_rejected() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        authorized.setStripePaymentIntentId(null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(authorized));

        assertThatThrownBy(() -> svc.capture(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);

        verify(stripePaymentProvider, never()).captureManualPaymentIntent(eq(null), anyString());
    }
}
