package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity;
import com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository;
import com.mannschaft.app.payment.stripe.CaptureMethod;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F22.1 謝礼決済 §6.3 第四陣 A: 次回入金相殺（未回収 Stripe 手数料の自動回収）の {@link ConnectChargeService} 単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。検証:
 * ① outstanding&gt;0 で次回 charge の PI application_fee が recovery 分増え outstanding が減る、
 * ② RECOVERY(回収実行) 仕訳が向き正しく（D PAYEE = C PLATFORM_FEE）追記＋複式借貸一致、
 * ③ 冪等（二重 charge/二重適用なし）、④ outstanding=0 なら無影響（既存 charge 不変・後方互換）、
 * ⑤ 回収上乗せ charge を ModeB 返金→recovery が outstanding へ再計上される、⑥ ModeA 返金では維持。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService 次回入金相殺（§6.3 第四陣 A・回収/繰越/冪等/再計上/隔離）")
class ConnectChargeServiceFeeRecoveryTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");
    private static final long ACTOR_USER_ID = 4242L;
    private static final long PAYEE_TEAM_ID = 77L;

    // 額面 10,000・DEFAULT policy → amount=10,250 / selfFee(totalFee)=500 / headroom = 10,250 − 500 = 9,750。
    private static final long FACE = 10_000L;
    private static final long AMOUNT = 10_250L;
    private static final long SELF_FEE = 500L;
    private static final long HEADROOM = AMOUNT - SELF_FEE; // 9,750

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver, feeRecoveryBalanceRepository);
    }

    private ConnectAccountEntity payeeAccount() {
        ConnectAccountEntity a = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(PAYEE_TEAM_ID).organizationId(5L)
                .stripeAccountId("acct_payee").payoutsEnabled(true).chargesEnabled(true)
                .build();
        a.setId(PAYEE_ACCOUNT_ID);
        return a;
    }

    /** 会費 charge コマンド（即時 AUTOMATIC・未 confirm・後方互換コンストラクタ）。 */
    private MembershipChargeCommand membershipCmd() {
        return new MembershipChargeCommand(FACE, PAYEE_ACCOUNT_ID, "cus_x", 999L, 100L, 5L, "idem-1");
    }

    private void givenDefaultPolicy() {
        lenient().when(feePolicyResolver.resolve(any(), any())).thenReturn(FeePolicy.defaultPolicy());
    }

    private void givenOutstanding(long amount) {
        FeeRecoveryBalanceEntity bal = FeeRecoveryBalanceEntity.builder()
                .connectAccountId(PAYEE_ACCOUNT_ID).organizationId(5L).currency("jpy")
                .outstandingAmount(amount).build();
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.of(bal));
    }

    // ============================================================================
    // ① outstanding > 0（headroom 以内）で次回 charge の PI application_fee が recovery 分増え outstanding が減る。
    // ② RECOVERY(回収実行) 仕訳が向き正しく追記＋借貸一致。
    // ============================================================================

    @Test
    @DisplayName("① outstanding=369（headroom 以内）で会費 charge → PI application_fee=selfFee500+369=869・escrow 列は selfFee500 据置・outstanding 369→0・② RECOVERY(D PAYEE=C PLATFORM_FEE=369) 借貸一致")
    void chargeWithOutstanding_upliftsPiFee_decrementsOutstanding_recordsRecovery() {
        ConnectChargeService svc = service();
        givenDefaultPolicy();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-1")).willReturn(Optional.empty());
        givenOutstanding(369L);
        given(stripePaymentProvider.createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(SELF_FEE + 369L), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1")))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_new", "cs_new", "requires_confirmation"));
        // 回収実行は未計上（純額 0）→ 適用される。
        given(ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(any())).willReturn(0L);
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
            EscrowTransactionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ESCROW_ID);
            }
            return e;
        });
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(membershipCmd());

        // ① PI の application_fee_amount は selfFee(500)+recovery(369)=869。escrow 列は selfFee=500 のまま（隔離）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(869L), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1"));
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getApplicationFeeAmount()).isEqualTo(SELF_FEE); // 隔離: 列は self のまま

        // ① outstanding を 369 減算（369 → 0）。
        ArgumentCaptor<FeeRecoveryBalanceEntity> balCaptor = ArgumentCaptor.forClass(FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(0L);

        // ② RECOVERY(回収実行) 仕訳: D PAYEE 369 = C PLATFORM_FEE 369（C1/C2 と逆向き）・借貸一致・stripe_object_id=piId。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        List<LedgerEntryEntity> rec = ledgerCaptor.getValue();
        assertThat(rec).allMatch(e -> e.getEntryType() == LedgerEntryType.RECOVERY);
        long d = rec.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long c = rec.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(d).isEqualTo(c).isEqualTo(369L);
        assertThat(rec).anyMatch(e -> e.getDirection() == LedgerDirection.D
                && e.getAccount() == LedgerAccount.PAYEE && e.getAmount() == 369L);
        assertThat(rec).anyMatch(e -> e.getDirection() == LedgerDirection.C
                && e.getAccount() == LedgerAccount.PLATFORM_FEE && e.getAmount() == 369L);
        assertThat(rec).allMatch(e -> "pi_new".equals(e.getStripeObjectId()));
    }

    @Test
    @DisplayName("① 部分回収＋繰越: outstanding=14,750（headroom 9,750 超）→ PI fee=selfFee500+9,750=10,250（chk_et_fee 等号上限）・outstanding 14,750→5,000（繰越）")
    void chargeWithOutstandingExceedingHeadroom_partialRecoveryWithCarryover() {
        ConnectChargeService svc = service();
        givenDefaultPolicy();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-1")).willReturn(Optional.empty());
        givenOutstanding(HEADROOM + 5_000L); // 14,750
        given(stripePaymentProvider.createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(AMOUNT), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1")))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_new", "cs_new", "requires_confirmation"));
        given(ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(any())).willReturn(0L);
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
            EscrowTransactionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ESCROW_ID);
            }
            return e;
        });
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(membershipCmd());

        // 上乗せは headroom(9,750) のみ → PI fee = 500 + 9,750 = 10,250 = amount（chk_et_fee 等号・不可侵）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(AMOUNT), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1"));

        // outstanding は headroom 分（9,750）だけ減算 → 14,750 − 9,750 = 5,000（繰越）。
        ArgumentCaptor<FeeRecoveryBalanceEntity> balCaptor = ArgumentCaptor.forClass(FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(5_000L);
    }

    // ============================================================================
    // ③ 冪等（二重適用なし）。
    // ============================================================================

    @Test
    @DisplayName("③ 冪等: 当該 escrow に既に回収実行（純額>0）あり → 二重回収しない（outstanding 減算なし・RECOVERY 仕訳追記なし）")
    void recoveryIdempotent_alreadyApplied_skips() {
        ConnectChargeService svc = service();
        givenDefaultPolicy();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-1")).willReturn(Optional.empty());
        givenOutstanding(369L);
        given(stripePaymentProvider.createDestinationPaymentIntent(anyLong(), anyString(), anyString(),
                anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_new", "cs_new", "requires_confirmation"));
        // 既に回収実行が立っている（純額 369 > 0）→ skip。
        given(ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(any())).willReturn(369L);
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
            EscrowTransactionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ESCROW_ID);
            }
            return e;
        });

        svc.charge(membershipCmd());

        // PI には上乗せされる（balance 読み取りは PI 作成前）が、記帳・残高減算は冪等 skip で一切走らない。
        verify(feeRecoveryBalanceRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    // ============================================================================
    // ④ outstanding=0 なら無影響（既存 charge 不変・後方互換）。
    // ============================================================================

    @Test
    @DisplayName("④ outstanding=0（残高行なし）→ PI fee=selfFee500 のまま・RECOVERY 仕訳なし・残高 save なし（通常 charge と完全不変＝後方互換）")
    void noOutstanding_chargeUnchanged() {
        ConnectChargeService svc = service();
        givenDefaultPolicy();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-1")).willReturn(Optional.empty());
        // 残高行なし → outstanding 0。
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(stripePaymentProvider.createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(SELF_FEE), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1")))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_new", "cs_new", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
            EscrowTransactionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ESCROW_ID);
            }
            return e;
        });

        svc.charge(membershipCmd());

        // PI fee = selfFee=500（上乗せ 0）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(eq(AMOUNT), eq("JPY"), eq("cus_x"),
                eq(SELF_FEE), eq("acct_payee"), eq(CaptureMethod.AUTOMATIC), eq("idem-1"));
        // 残高 save も RECOVERY 仕訳も冪等判定も一切走らない（recovery=0 で早期 return）。
        verify(feeRecoveryBalanceRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(ledgerEntryRepository, never()).sumAppliedRecoveryNetOnEscrow(any());
    }

    // ============================================================================
    // ⑤ 回収上乗せ charge を ModeB 返金 → recovery が outstanding へ再計上される。
    // ⑥ ModeA 返金では維持（再計上なし）。
    // ============================================================================

    private EscrowTransactionEntity capturedRecoveryBearingEscrow() {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .organizationId(5L)
                .faceAmount(FACE).amount(AMOUNT).applicationFeeAmount(SELF_FEE) // 列は self のまま（回収は別管理）
                .currency("JPY").status(EscrowStatus.CAPTURED).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    @Test
    @DisplayName("⑤ ModeB 返金: 回収を上乗せした charge（回収実行純額369）を ModeB 全額返金 → recovery 369 が outstanding へ再計上（逆仕訳 D PLATFORM_FEE=C PAYEE=369）")
    void modeBRefund_recapitalizesAppliedRecovery() {
        ConnectChargeService svc = service();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID))
                .willReturn(Optional.of(capturedRecoveryBearingEscrow()));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(AMOUNT), anyString(),
                eq(true), eq(true), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_b", "pending"));
        // C1: 実手数料 pending（残高計上をスキップ）にして、再計上のみを純粋に観測する。
        given(stripePaymentProvider.retrieveChargeProcessingFee("pi_abc"))
                .willReturn(StripePaymentProvider.PROCESSING_FEE_PENDING);
        // 当該 escrow に立っている回収実行の純額 = 369（再計上対象）。
        given(ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(ESCROW_ID)).willReturn(369L);
        given(feeRecoveryBalanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(PAYEE_ACCOUNT_ID, "jpy"))
                .willReturn(Optional.empty());
        given(feeRecoveryBalanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYEE, "cancellation", null, ACTOR_USER_ID);

        // outstanding へ 369 再計上（新規行 outstanding=369）。
        ArgumentCaptor<FeeRecoveryBalanceEntity> balCaptor = ArgumentCaptor.forClass(FeeRecoveryBalanceEntity.class);
        verify(feeRecoveryBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getOutstandingAmount()).isEqualTo(369L);

        // 逆仕訳（D PLATFORM_FEE = C PAYEE = 369）が借貸一致で追記される（回収実行の打ち消し）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository, times(2)).saveAll(ledgerCaptor.capture()); // 既存返金バッチ + 再計上バッチ
        List<LedgerEntryEntity> recap = ledgerCaptor.getAllValues().get(1);
        assertThat(recap).allMatch(e -> e.getEntryType() == LedgerEntryType.RECOVERY);
        assertThat(recap).anyMatch(e -> e.getDirection() == LedgerDirection.D
                && e.getAccount() == LedgerAccount.PLATFORM_FEE && e.getAmount() == 369L);
        assertThat(recap).anyMatch(e -> e.getDirection() == LedgerDirection.C
                && e.getAccount() == LedgerAccount.PAYEE && e.getAmount() == 369L);
    }

    @Test
    @DisplayName("⑥ ModeA 返金: refund_application_fee=false で recovery 据置 → 再計上しない（sumAppliedRecovery を読まない・outstanding save なし）")
    void modeARefund_keepsRecovery_noRecapitalize() {
        ConnectChargeService svc = service();
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount()));
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID))
                .willReturn(Optional.of(capturedRecoveryBearingEscrow()));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of());
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent("pi_abc")).willReturn("tr_xyz");
        given(stripePaymentProvider.createConnectRefund(eq("pi_abc"), eq(HEADROOM), anyString(),
                eq(false), eq(false), anyString()))
                .willReturn(new StripePaymentProvider.ConnectRefundInfo("re_a", "pending"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        svc.refund(ESCROW_ID, null, FeeBearer.PAYER, "cancellation", null, ACTOR_USER_ID);

        // ModeA は recovery を維持する → 再計上ロジックを呼ばない（sumAppliedRecovery 読まない・残高 save なし）。
        verify(ledgerEntryRepository, never()).sumAppliedRecoveryNetOnEscrow(any());
        verify(feeRecoveryBalanceRepository, never()).save(any());
        // 既存返金バッチ（ModeA）は 1 回のみ。
        verify(ledgerEntryRepository, times(1)).saveAll(any());
    }
}
