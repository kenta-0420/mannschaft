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
    @Mock private com.mannschaft.app.payment.FeePolicyResolver feePolicyResolver;
    @Mock private com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");
    private static final long ACTOR_USER_ID = 4242L;
    private static final long PAYEE_TEAM_ID = 77L;

    private ConnectChargeService service() {
        // 返金は escrow 保存値（amount − application_fee）ベースで rate 非依存ゆえ resolver は未使用（モックのみ渡す）。
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver, feeRecoveryBalanceRepository);
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
    @DisplayName("CAPTURED 全額返金（支払者負担モデル・decouple）: 支払者へ transferAmount=9,750 返金（reverse_transfer=false/refund_application_fee=false）＋同額の TransferReversal で受取側±0/Mannschaft±0・REFUNDED・refunds PENDING・ledger 借貸一致(9,750)")
    void capturedFullRefund_payerBearsFee_decoupleReversalAndRefund() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        EscrowTransactionEntity captured = escrow(EscrowStatus.CAPTURED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(captured));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        // 送金 ID は PaymentIntent → latest_charge → charge.transfer で解決される。
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_xyz");
        // 全額返金で支払者へ戻す額 = transferAmount = amount(10,250) − application_fee(500) = 9,750。
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(9_750L), eq("cancellation"),
                eq(false), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_1", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        // null=全額。支払者へ戻す額は残額（transferAmount 全部）。
        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

        // (1) 受取側送金から 9,750 を「明示的に」巻き戻す（比例 reverse の取りこぼし回避＝Mannschaft±0/受取側±0）。
        ArgumentCaptor<String> revKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).reverseTransfer(eq("tr_xyz"), eq(9_750L), revKeyCaptor.capture());
        assertThat(revKeyCaptor.getValue()).startsWith("reversal-" + ESCROW_ID + "-");

        // (2) 支払者へ 9,750 返金。reverse_transfer=false（比例 reverse 不使用）/ refund_application_fee=false（1.4% keep）。
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).createConnectRefund(eq("pi_abc"), eq(9_750L), eq("cancellation"),
                eq(false), eq(false), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("refund-" + ESCROW_ID + "-");

        // 全額返金（累計=transferAmount）→ REFUNDED。
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);

        // refunds に PENDING で記録（webhook で SUCCEEDED 確定）。amount=支払者へ戻した額（transferベース）=9,750。
        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refundCaptor.getValue().getStripeRefundId()).isEqualTo("re_1");
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(9_750L);
        assertThat(refundCaptor.getValue().getEscrowTransactionId()).isEqualTo(ESCROW_ID);
        assertThat(refundCaptor.getValue().getRefundedByUserId()).isEqualTo(ACTOR_USER_ID);

        // ledger(REFUND) 監査追記・借貸一致（D PAYEE=巻き戻し / C PAYER=支払者返金 = いずれも 9,750）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> entries = ledgerCaptor.getValue();
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        // 不変条件: 受取側が被る額（D PAYEE）= 支払者へ戻す額（C PAYER）= 9,750 → Mannschaft±0。
        assertThat(debit).isEqualTo(credit).isEqualTo(9_750L);
        assertThat(entries).allMatch(e -> e.getEntryType() == LedgerEntryType.REFUND);
        assertThat(entries).allMatch(e -> ESCROW_ID.equals(e.getEscrowTransactionId()));

        // ③ ModeA（PAYER）は実 Stripe 手数料の取得も未回収残高計上も一切行わない（§6.3 C1 の隔離・不変）。
        verify(stripePaymentProvider, never()).retrieveChargeProcessingFee(anyString());
        verify(feeRecoveryBalanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("CAPTURED 部分返金: 支払者へ R=3,000 返金＋同額 TransferReversal・PARTIALLY_REFUNDED・refunds=3,000（不変条件: 受取側負担=支払者戻り）")
    void capturedPartialRefund_partiallyRefunded() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_xyz");
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(3_000L), anyString(),
                eq(false), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_2", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 3_000L, FeeBearer.PAYER, "requested_by_customer", null, ACTOR_USER_ID);

        // 巻き戻し額（受取側負担）= 支払者へ戻す額 = 3,000（Mannschaft±0）。
        verify(stripePaymentProvider).reverseTransfer(eq("tr_xyz"), eq(3_000L), anyString());

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.PARTIALLY_REFUNDED);

        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("PARTIALLY_REFUNDED から残額ちょうど返金: 累計=transferAmount(9,750) で REFUNDED")
    void partialThenRemainder_becomesRefunded() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.PARTIALLY_REFUNDED)));
        // 既に 3,000 返金済み（transferベース）。残額 = 9,750 − 3,000 = 6,750。
        RefundEntity prior = RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId("re_prior")
                .amount(3_000L).currency("JPY").reason("requested_by_customer").status(RefundStatus.SUCCEEDED).build();
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of(prior));
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_xyz");
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(6_750L), anyString(),
                eq(false), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_3", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 6_750L, FeeBearer.PAYER, "requested_by_customer", null, ACTOR_USER_ID);

        verify(stripePaymentProvider).reverseTransfer(eq("tr_xyz"), eq(6_750L), anyString());
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);
    }

    @Test
    @DisplayName("超過拒否: 残額（transferAmount−既返金）を超える返金→REFUND_AMOUNT_EXCEEDS・Stripe never")
    void exceedsResidual_rejected() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.PARTIALLY_REFUNDED)));
        // 既に 8,000 返金済み（transferベース）。残額 = 9,750 − 8,000 = 1,750。
        RefundEntity prior = RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId("re_prior")
                .amount(8_000L).currency("JPY").reason("requested_by_customer").status(RefundStatus.SUCCEEDED).build();
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of(prior));

        // 残額 1,750 に対し 3,000 を要求 → 超過。
        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 3_000L, FeeBearer.PAYER, "requested_by_customer", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.REFUND_AMOUNT_EXCEEDS);

        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
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

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 0L, FeeBearer.PAYER, "requested_by_customer", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.REFUND_AMOUNT_EXCEEDS);

        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("整合性異常: CAPTURED だが Transfer 未解決→INVALID_ESCROW_STATE・支払者返金には進まない（reverseTransfer/createConnectRefund never）")
    void transferUnresolved_invalidState() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        // 送金 ID が解決できない（capture 済みなのに transfer なし＝整合性異常）。
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn(null);

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);

        // 巻き戻しできないため支払者返金にも進まない（Mannschaft の一時的持ち出しを防ぐ）。
        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AUTHORIZED（capture 前）で返金要求→cancelAuthorization 呼び・CANCELLED・createConnectRefund never・refunds 記録なし")
    void authorizedBeforeCapture_cancelsAuthorization() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(authorized));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

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

        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

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

        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

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

        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

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

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID))
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

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 5_000L, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);

        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
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

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 5_000L, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("IDOR: USER 受領（個人）の明示返金は本波未提供→404秘匿（PAYMENT_C002）・Stripe never・テスト網羅の穴埋め")
    void payeeUser_notFound() {
        ConnectChargeService svc = service();
        // 受取側 Connect 口座が USER scope（個人受領）。明示返金 API では存在を漏らさず 404 秘匿で拒否する。
        ConnectAccountEntity userPayee = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.USER).scopeId(12345L).organizationId(5L)
                .stripeAccountId("acct_user").payoutsEnabled(true).chargesEnabled(true)
                .build();
        userPayee.setId(PAYEE_ACCOUNT_ID);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(userPayee));

        assertThatThrownBy(() -> svc.refund(ESCROW_ID, 5_000L, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        // 認可（scope ADMIN）も Stripe も呼ばない。
        verify(accessControlService, never()).checkPermission(anyLong(), anyLong(), anyString(), anyString());
        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        verify(stripePaymentProvider, never()).createConnectRefund(anyString(), anyLong(), anyString(),
                anyBoolean(), anyBoolean(), anyString());
        verify(refundRepository, never()).save(any());
    }

    // ============================================================================
    // モードB＝受取側負担（PAYEE）: 支払者満額返金（chargeAmount）＋ refund_application_fee=true（1.4% 放棄・中立化）。
    //   ⚠️ Stripe 仕様: 元取引の決済手数料（≈369）は返金されず標準フローでは Mannschaft 一時負担（受取側残高からの
    //      追加再徴収は TransferReversal 上限／Account Debits 要件のため返金 1 件ごとの自動化は不可）。
    //      Stripe 引数は amount=grossRefund / reverse_transfer=true / refund_application_fee=true を実 assert。
    //      明示 TransferReversal は呼ばない（reverse_transfer=true が送金巻き戻しを担う＝二重巻き戻し防止）。
    // ============================================================================

    @Test
    @DisplayName("モードB CAPTURED 全額返金: 支払者へ満額 chargeAmount=10,250（reverse_transfer=true/refund_application_fee=true）・明示 TransferReversal なし・REFUNDED・refunds.amount=R(9,750 transferベース)・ledger 借貸一致(D PAYER 10,250 = C PAYEE 9,750 + C PLATFORM_FEE 500)")
    void capturedFullRefund_payeeBearsFee_grossRefundFull() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        // モードB 全額: grossRefund = chargeAmount = amount(10,250)。reverse_transfer=true / refund_application_fee=true。
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(10_250L), eq("cancellation"),
                eq(true), eq(true), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_b1", "pending"));
        // §6.3 C1: 元 charge の実 Stripe 手数料 369（minor・正値）。全額返金なので比例計上額＝369 をそのまま受取側へ計上。
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYEE, "cancellation", null, ACTOR_USER_ID);

        // Stripe 引数の実 assert: 支払者へ満額 10,250・reverse_transfer=true・refund_application_fee=true。
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).createConnectRefund(eq("pi_abc"), eq(10_250L), eq("cancellation"),
                eq(true), eq(true), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("refund-" + ESCROW_ID + "-");

        // モードB は明示 TransferReversal を呼ばない（reverse_transfer=true が送金巻き戻しを担う＝二重巻き戻し防止）。
        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());
        // Transfer 解決も不要（モードA decouple のみ使用）。
        verify(stripePaymentProvider, never()).resolveTransferIdFromPaymentIntent(anyString());

        // 全額（累計=transferAmount 9,750）→ REFUNDED。
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);

        // refunds.amount は精算額 R（transferベース・両モード共通の残額管理）= 9,750。webhook 確定ロジックと整合。
        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(9_750L);
        assertThat(refundCaptor.getValue().getStripeRefundId()).isEqualTo("re_b1");

        // ledger は 2 バッチ: (1) 既存の返金バッチ（不変・借貸一致）、(2) §6.3 C1 の RECOVERY バッチ（実手数料）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository, org.mockito.Mockito.times(2)).saveAll(ledgerCaptor.capture());
        List<List<LedgerEntryEntity>> allBatches = ledgerCaptor.getAllValues();

        // (1) 既存返金バッチ（不変）: D PAYER 10,250 = C PAYEE 9,750 + C PLATFORM_FEE 500。借貸一致。
        List<LedgerEntryEntity> entries = allBatches.get(0);
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(10_250L);
        long payerDebit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.D && e.getAccount() == LedgerAccount.PAYER)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long payeeCredit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.C && e.getAccount() == LedgerAccount.PAYEE)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long platformCredit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.C && e.getAccount() == LedgerAccount.PLATFORM_FEE
                        && e.getEntryType() == LedgerEntryType.FEE)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(payerDebit).isEqualTo(10_250L);   // 支払者満額返金
        assertThat(payeeCredit).isEqualTo(9_750L);    // 受取側送金巻き戻し（R）
        assertThat(platformCredit).isEqualTo(500L);   // Mannschaft が放棄/一時負担する margin（application_fee 分・FEE 種別）
        assertThat(entries).allMatch(e -> e.getEntryType() == LedgerEntryType.REFUND
                || e.getEntryType() == LedgerEntryType.FEE);

        // (2) §6.3 C1 RECOVERY バッチ（実手数料 369 を別仕訳で計上・自己完結で借貸一致）。
        List<LedgerEntryEntity> recovery = allBatches.get(1);
        assertThat(recovery).allMatch(e -> e.getEntryType() == LedgerEntryType.RECOVERY);
        long recDebit = recovery.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long recCredit = recovery.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        // 借貸一致: D PLATFORM_FEE 369 = C PAYEE 369（受取側からの未回収＝receivable）。
        assertThat(recDebit).isEqualTo(recCredit).isEqualTo(369L);
        assertThat(recovery).anyMatch(e -> e.getDirection() == LedgerDirection.D
                && e.getAccount() == LedgerAccount.PLATFORM_FEE && e.getAmount() == 369L);
        assertThat(recovery).anyMatch(e -> e.getDirection() == LedgerDirection.C
                && e.getAccount() == LedgerAccount.PAYEE && e.getAmount() == 369L);

        // 未回収残高: 新規行を作成し outstanding_amount=369（payee×jpy・organization_id 埋め込み）。
        ArgumentCaptor<com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity> balCaptor =
                ArgumentCaptor.forClass(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(369L);
        assertThat(balCaptor.getValue().getConnectAccountId()).isEqualTo(PAYEE_ACCOUNT_ID);
        assertThat(balCaptor.getValue().getCurrency()).isEqualTo("jpy");
        assertThat(balCaptor.getValue().getOrganizationId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("モードB CAPTURED 部分返金: R=4,875（transferの半分）→ 支払者へ gross=round(10,250×4,875/9,750)=5,125・reverse_transfer=true/refund_application_fee=true・PARTIALLY_REFUNDED・refunds.amount=R(4,875)・ledger 借貸一致(D PAYER 5,125 = C PAYEE 4,875 + C PLATFORM_FEE 250)")
    void capturedPartialRefund_payeeBearsFee_grossUpProportional() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        // 部分: R=4,875（残額 9,750 未満）→ grossRefund = round(10,250 × 4,875 / 9,750) = round(5,125.0) = 5,125。
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(5_125L), anyString(),
                eq(true), eq(true), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_b2", "pending"));
        // §6.3 C1: 実手数料 369。部分返金（gross=5,125 / charge=10,250）→ 比例計上 round(369×5,125/10,250)=185。
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, 4_875L, FeeBearer.PAYEE, "requested_by_customer", null, ACTOR_USER_ID);

        verify(stripePaymentProvider).createConnectRefund(eq("pi_abc"), eq(5_125L), anyString(),
                eq(true), eq(true), anyString());
        verify(stripePaymentProvider, never()).reverseTransfer(anyString(), anyLong(), anyString());

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.PARTIALLY_REFUNDED);

        // refunds.amount は精算額 R（transferベース）= 4,875。
        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmount()).isEqualTo(4_875L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository, org.mockito.Mockito.times(2)).saveAll(ledgerCaptor.capture());
        List<List<LedgerEntryEntity>> allBatches = ledgerCaptor.getAllValues();

        // (1) 既存返金バッチ（不変）: D PAYER 5,125 = C PAYEE 4,875（R）+ C PLATFORM_FEE 250（gross−R＝Mannschaft 放棄分）。
        List<LedgerEntryEntity> entries = allBatches.get(0);
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(5_125L);
        long platformCredit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.C && e.getAccount() == LedgerAccount.PLATFORM_FEE
                        && e.getEntryType() == LedgerEntryType.FEE)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(platformCredit).isEqualTo(250L);

        // (2) §6.3 C1 RECOVERY バッチ: 比例計上 185 を自己完結仕訳（D PLATFORM_FEE = C PAYEE = 185）。
        List<LedgerEntryEntity> recovery = allBatches.get(1);
        assertThat(recovery).allMatch(e -> e.getEntryType() == LedgerEntryType.RECOVERY);
        long recDebit = recovery.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long recCredit = recovery.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(recDebit).isEqualTo(recCredit).isEqualTo(185L);

        // 未回収残高 outstanding_amount=185（比例計上）。
        ArgumentCaptor<com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity> balCaptor =
                ArgumentCaptor.forClass(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(185L);
    }

    @Test
    @DisplayName("feeBearer=null は既定モードA（PAYER）として扱う: 明示 TransferReversal＋reverse_transfer=false/refund_application_fee=false（既存挙動の後方互換）")
    void nullFeeBearer_defaultsToPayerMode() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_xyz");
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(9_750L), anyString(),
                eq(false), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_def", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        // feeBearer=null → 既定 PAYER（モードA・decouple）。
        svc.refund(ESCROW_ID, null, null, "cancellation", null, ACTOR_USER_ID);

        // モードA: 明示 TransferReversal(9,750)＋reverse_transfer=false/refund_application_fee=false。
        verify(stripePaymentProvider).reverseTransfer(eq("tr_xyz"), eq(9_750L), anyString());
        verify(stripePaymentProvider).createConnectRefund(eq("pi_abc"), eq(9_750L), anyString(),
                eq(false), eq(false), anyString());

        // ③ 既定 ModeA でも実手数料取得/残高計上はしない（§6.3 C1 の隔離・不変）。
        verify(stripePaymentProvider, never()).retrieveChargeProcessingFee(anyString());
        verify(feeRecoveryBalanceRepository, never()).save(any());
    }

    // ============================================================================
    // §6.3 第二陣 C1: ModeB 実 Stripe 手数料の未回収残高計上（追加テスト）。
    // ============================================================================

    @Test
    @DisplayName("§6.3 C1: 既存残高への加算（upsert）— 既に outstanding=1,000 の payee 行へ実手数料 369 を加算し 1,369 にする（複式整合維持・既存 organization_id 温存）")
    void modeBFeeRecovery_upsertExistingBalance_accumulates() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(10_250L), anyString(),
                eq(true), eq(true), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_b3", "pending"));
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc")).willReturn(369L);
        // 既存残高 1,000（同 payee×jpy）。加算で 1,369 になるべき。
        com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity existing =
                com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity.builder()
                        .connectAccountId(PAYEE_ACCOUNT_ID).organizationId(5L).currency("jpy")
                        .outstandingAmount(1_000L).build();
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.of(existing));
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYEE, "cancellation", null, ACTOR_USER_ID);

        // 既存行へ 369 加算 → 1,369。新規行は作らない（同一 entity を save）。
        ArgumentCaptor<com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity> balCaptor =
                ArgumentCaptor.forClass(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(1_369L);
        assertThat(balCaptor.getValue().getOrganizationId()).isEqualTo(5L);
        // RECOVERY 仕訳も借貸一致で記帳される（既存返金バッチ＋RECOVERY バッチ＝2 回）。
        verify(ledgerEntryRepository, org.mockito.Mockito.times(2)).saveAll(any());
    }

    @Test
    @DisplayName("§6.3 C1: 実手数料が pending（未確定）の場合は残高計上をスキップし RECOVERY 仕訳も追記しない（0 で握り潰さない・既存返金会計は不変）")
    void modeBFeeRecovery_pendingFee_skipsBalanceAndRecoveryLedger() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(10_250L), anyString(),
                eq(true), eq(true), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_b4", "pending"));
        // balance_transaction 未確定 → PROCESSING_FEE_PENDING を返す。
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc"))
                .willReturn(StripePaymentProvider.PROCESSING_FEE_PENDING);
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYEE, "cancellation", null, ACTOR_USER_ID);

        // 残高計上なし・balance 行を引きにいかない・RECOVERY 仕訳も追記しない（既存返金バッチ 1 回のみ）。
        verify(feeRecoveryBalanceRepository, never()).save(any());
        verify(feeRecoveryBalanceRepository, never())
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(any(), anyString());
        verify(ledgerEntryRepository, org.mockito.Mockito.times(1)).saveAll(any());
    }

    @Test
    @DisplayName("§6.3 C1: 冪等 — 既 REFUNDED の二重返金は no-op で実手数料取得も残高計上も走らない（同一返金で二重計上しない）")
    void modeBFeeRecovery_alreadyRefunded_noDoubleCount() {
        ConnectChargeService svc = service();
        givenPayeeResolves();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(escrow(EscrowStatus.REFUNDED)));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYEE, "cancellation", null, ACTOR_USER_ID);

        // 終端状態冪等 → Stripe も残高計上も一切呼ばない（二重計上の根本防止）。
        verify(stripePaymentProvider, never()).retrieveChargeProcessingFee(anyString());
        verify(feeRecoveryBalanceRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }
}
