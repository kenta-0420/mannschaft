package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-c 第二波: {@link ConnectChargeService#refund}（返金・与信取消）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。
 * 設定A 厳守: CAPTURED 返金は {@code reverse_transfer=true}/{@code refund_application_fee=false} が
 * Stripe へ渡ること、額面ベースの残額管理（部分→PARTIALLY_REFUNDED・累計追跡・超過拒否）、
 * capture 前（AUTHORIZED/HELD）は返金でなく与信取消（{@code cancelAuthorization}・{@code createConnectRefund} never）、
 * 既 REFUNDED/CANCELLED は冪等、IDOR（非受取側 ADMIN/無関係 scope→拒否・Stripe never）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService.refund 単体テスト（返金 / 与信取消・設定A）")
class ConnectChargeServiceRefundTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");
    private static final long ACTOR_USER_ID = 4242L;
    private static final long PAYEE_TEAM_ID = 77L;

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver());
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

    private ConnectAccountEntity payeeAccount() {
        ConnectAccountEntity a = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(PAYEE_TEAM_ID).organizationId(5L)
                .stripeAccountId("acct_payee").payoutsEnabled(true).chargesEnabled(true)
                .build();
        a.setId(PAYEE_ACCOUNT_ID);
        return a;
    }

    private void givenPayeeResolves() {
        lenient().when(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).thenReturn(Optional.of(payeeAccount()));
    }

    @Test
    @DisplayName("CAPTURED 全額返金: reverse_transfer=true / refund_application_fee=false で Stripe 返金・REFUNDED・refunds PENDING 記録・ledger 借貸一致")
    void capturedFullRefund_setsConfigAAndRefunded() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        EscrowTransactionEntity captured = escrow(EscrowStatus.CAPTURED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(captured));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(10_000L), eq("cancellation"),
                eq(true), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_1", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 10_000L, "cancellation", null, ACTOR_USER_ID);

        // 設定A: reverse_transfer=true / refund_application_fee=false が Stripe へ渡る。
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).createConnectRefund(eq("pi_abc"), eq(10_000L), eq("cancellation"),
                eq(true), eq(false), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("refund-" + ESCROW_ID + "-");

        // 全額返金 → REFUNDED。
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);

        // refunds に PENDING で記録（webhook で SUCCEEDED 確定）。
        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refundCaptor.getValue().getStripeRefundId()).isEqualTo("re_1");
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(10_000L);
        assertThat(refundCaptor.getValue().getEscrowTransactionId()).isEqualTo(ESCROW_ID);
        assertThat(refundCaptor.getValue().getRefundedByUserId()).isEqualTo(ACTOR_USER_ID);

        // ledger(REFUND) 監査追記・借貸一致。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> entries = ledgerCaptor.getValue();
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(10_000L);
        assertThat(entries).allMatch(e -> e.getEntryType() == LedgerEntryType.REFUND);
        assertThat(entries).allMatch(e -> ESCROW_ID.equals(e.getEscrowTransactionId()));
    }

    @Test
    @DisplayName("CAPTURED 部分返金: PARTIALLY_REFUNDED・額面ベースで refunds 記録")
    void capturedPartialRefund_partiallyRefunded() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(3_000L), anyString(),
                eq(true), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_2", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 3_000L, "requested_by_customer", null, ACTOR_USER_ID);

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.PARTIALLY_REFUNDED);

        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("PARTIALLY_REFUNDED から残額ちょうど返金: 累計=face_amount で REFUNDED")
    void partialThenRemainder_becomesRefunded() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.PARTIALLY_REFUNDED)));
        // 既に 3000 返金済み。残額 7000。
        RefundEntity prior = RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId("re_prior")
                .amount(3_000L).currency("JPY").reason("requested_by_customer").status(RefundStatus.SUCCEEDED).build();
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of(prior));
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(7_000L), anyString(),
                eq(true), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_3", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 7_000L, "requested_by_customer", null, ACTOR_USER_ID);

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);
    }

    @Test
    @DisplayName("超過拒否: 残額（face−既返金）を超える返金→REFUND_AMOUNT_EXCEEDS・Stripe never")
    void exceedsResidual_rejected() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.PARTIALLY_REFUNDED)));
        RefundEntity prior = RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId("re_prior")
                .amount(8_000L).currency("JPY").reason("requested_by_customer").status(RefundStatus.SUCCEEDED).build();
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of(prior));

        // 残額 2000 に対し 3000 を要求 → 超過。
        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 3_000L, "requested_by_customer", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.REFUND_AMOUNT_EXCEEDS);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("amount<=0 の不正要求→REFUND_AMOUNT_EXCEEDS・Stripe never")
    void nonPositiveAmount_rejected() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 0L, "requested_by_customer", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.REFUND_AMOUNT_EXCEEDS);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("AUTHORIZED（capture 前）で返金要求→cancelAuthorization 呼び・CANCELLED・createConnectRefund never・refunds 記録なし")
    void authorizedBeforeCapture_cancelsAuthorization() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(authorized));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, "cancellation", null, ACTOR_USER_ID);

        verify(stripePaymentProvider).cancelAuthorization("pi_abc", "cancel-" + ESCROW_ID);
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        assertThat(escrowCaptor.getValue().getCancelledAt()).isNotNull();

        // 課金が起きていないため refunds には記録しない。
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("HELD（capture 前・PI 未作成）で返金要求→与信取消で CANCELLED・Stripe refund never")
    void heldBeforeCapture_cancels() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        EscrowTransactionEntity held = escrow(EscrowStatus.HELD);
        held.setStripePaymentIntentId(null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(held));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, "cancellation", null, ACTOR_USER_ID);

        // PI 未作成のため Stripe cancel も呼ばず、状態のみ CANCELLED にする。
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("冪等: 既 REFUNDED→no-op（Stripe never・例外なし）")
    void alreadyRefunded_noOp() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.REFUNDED)));

        svc.refund(ESCROW_ID, null, "cancellation", null, ACTOR_USER_ID);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
        verify(refundRepository, never()).save(any());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("冪等: 既 CANCELLED→no-op（Stripe never）")
    void alreadyCancelled_noOp() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CANCELLED)));

        svc.refund(ESCROW_ID, null, "cancellation", null, ACTOR_USER_ID);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("escrow 不在→404秘匿（PAYMENT_C002）・Stripe never")
    void missingEscrow_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, null, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("IDOR: 受取側 scope の ADMIN でない→PAYMENT_FORBIDDEN（403相当）・Stripe never")
    void notPayeeAdmin_forbidden() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        // 受取側（TEAM 77）の ADMIN チェックが失敗する。
        org.mockito.BDDMockito.willThrow(new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN))
                .given(accessControlService).checkPermission(eq(ACTOR_USER_ID), eq(PAYEE_TEAM_ID),
                        eq(PayeeScopeResolver.SCOPE_TYPE_TEAM), anyString());

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 5_000L, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("IDOR: 受取側 Connect 口座が解決できない（無関係 escrow）→404秘匿・Stripe never")
    void payeeAccountMissing_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 5_000L, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }
}
