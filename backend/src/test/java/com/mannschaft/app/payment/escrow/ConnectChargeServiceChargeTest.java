package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P1 Wave0: {@link ConnectChargeService#charge(MembershipChargeCommand)}（会費の即時 charge）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。
 * 検証:</p>
 * <ul>
 *   <li>即時 charge で escrow が MEMBERSHIP/AUTOMATIC・hold_expires_at=NULL で起票され、Stripe へ
 *       {@link CaptureMethod#AUTOMATIC} と idempotencyKey が渡る。</li>
 *   <li>手数料折半（額面 10,000 → charge 10,250 / app_fee 500 / transfer 9,750）が escrow に反映。</li>
 *   <li>受領口座が未 READY（payouts_enabled=false）→ HELD にせず {@code ONBOARDING_NOT_READY}（409）・PI never。</li>
 *   <li>冪等: 同一 sourceId（会費項目）で二重起票しない・PI 再作成も escrow 再保存もしない。</li>
 *   <li><b>ledger を charge() 時点で二重に書かない</b>（複式記帳は succeeded webhook に委ねる）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService.charge 単体テスト（会費・即時）")
class ConnectChargeServiceChargeTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private FeePolicyResolver feePolicyResolver;

    // PaymentFeeCalculator は純粋関数。実体を使い手数料式の一元利用を検証する。
    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    private ConnectChargeService service() {
        // R1: 会費は MEMBERSHIP の DEFAULT（率5%＋固定0）を解決し、額面10,000→appFee500 の後方互換を保つ。
        // 早期 throw 経路（口座未READY/不在・非正額面・冪等）では resolve 未到達のため lenient で許容する。
        org.mockito.Mockito.lenient().when(feePolicyResolver.resolve(any(), any())).thenReturn(FeePolicy.defaultPolicy());
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new com.mannschaft.app.payment.connect.PayeeScopeResolver(), feePolicyResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository.class));
    }

    private ConnectAccountEntity payeeAccount(boolean payoutsEnabled) {
        ConnectAccountEntity e = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(10L)
                .organizationId(55L)
                .stripeAccountId("acct_payee")
                .onboardingStatus(payoutsEnabled ? OnboardingStatus.READY : OnboardingStatus.ONBOARDING)
                .chargesEnabled(payoutsEnabled)
                .payoutsEnabled(payoutsEnabled)
                .country("JP")
                .defaultCurrency("JPY")
                .build();
        e.setId(PAYEE_ACCOUNT_ID);
        return e;
    }

    private MembershipChargeCommand command() {
        // faceAmount=10,000 / payee=PAYEE_ACCOUNT_ID / payer=cus_payer・userId=999 / 会費項目=777 / org=55。
        return new MembershipChargeCommand(
                10_000L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, "idem-membership-777");
    }

    @Test
    @DisplayName("即時 charge: MEMBERSHIP/AUTOMATIC・hold_expires_at=NULL で起票し AUTOMATIC+idempotencyKey を Stripe へ渡す")
    void charge_membershipAutomatic() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_mem", "pi_mem_secret", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MembershipChargeResult result = svc.charge(command());

        // PaymentFeeCalculator 経由の額が Stripe へ渡る（再実装でなく一元利用）・CaptureMethod=AUTOMATIC・idempotencyKey 橋渡し。
        verify(stripePaymentProvider).createDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.AUTOMATIC), eq("idem-membership-777"));

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        assertThat(saved.getSourceKind()).isEqualTo(EscrowSourceKind.MEMBERSHIP);
        assertThat(saved.getCaptureMode()).isEqualTo(EscrowCaptureMode.AUTOMATIC);
        assertThat(saved.getHoldExpiresAt()).isNull();
        assertThat(saved.getSourceParticipantId()).isNull();
        // 払い手主体は USER 固定・payer_scope_id=payerUserId。
        assertThat(saved.getPayerScopeKind()).isEqualTo(ScopeKind.USER);
        assertThat(saved.getPayerScopeId()).isEqualTo(999L);
        // 受領は解決した Connect 口座の scope/口座 ID を反映。
        assertThat(saved.getPayeeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(saved.getPayeeConnectAccountId()).isEqualTo(PAYEE_ACCOUNT_ID);
        assertThat(saved.getOrganizationId()).isEqualTo(55L);
        assertThat(saved.getStripePaymentIntentId()).isEqualTo("pi_mem");
        // R2-2: 業務冪等キーを escrow へ焼き付ける（次回の二重送信を dedup する基点）。
        assertThat(saved.getStripeIdempotencyKey()).isEqualTo("idem-membership-777");

        // clientSecret は払い手本人へ返す。
        assertThat(result.escrowTransactionId()).isEqualTo(saved.getId());
        assertThat(result.clientSecret()).isEqualTo("pi_mem_secret");
        assertThat(result.paymentIntentId()).isEqualTo("pi_mem");
        assertThat(result.status()).isEqualTo(EscrowStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("手数料折半: 額面10,000→charge10,250/appFee500/transfer9,750 が escrow に反映")
    void charge_feeSplitReflectedInEscrow() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_mem", "pi_mem_secret", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(command());

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        assertThat(saved.getFaceAmount()).isEqualTo(10_000L);
        assertThat(saved.getAmount()).isEqualTo(10_250L);
        assertThat(saved.getApplicationFeeAmount()).isEqualTo(500L);
        // 受取側着金 = amount − application_fee = 9,750。
        assertThat(saved.getAmount() - saved.getApplicationFeeAmount()).isEqualTo(9_750L);
    }

    @Test
    @DisplayName("受領口座未 READY(payouts_enabled=false)→ HELD にせず ONBOARDING_NOT_READY(409)・PI never・escrow 未保存")
    void charge_payeeNotReady_rejected() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(false)));

        assertThatThrownBy(() -> svc.charge(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);

        // 即時モードゆえ HELD にせずエラー → PI は一切作らず escrow も保存しない。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("冪等(R2-2): 同一 idempotencyKey の二重送信→既存を返し PI 再作成も escrow 再保存もしない")
    void charge_idempotentByIdempotencyKey() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity existing = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP).sourceId(777L)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(EscrowStatus.CAPTURED)
                .stripePaymentIntentId("pi_existing")
                .stripeIdempotencyKey("idem-membership-777")
                .build();
        UUID existingId = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
        existing.setId(existingId);
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.of(existing));

        MembershipChargeResult result = svc.charge(command());

        assertThat(result.escrowTransactionId()).isEqualTo(existingId);
        assertThat(result.paymentIntentId()).isEqualTo("pi_existing");
        assertThat(result.status()).isEqualTo(EscrowStatus.CAPTURED);
        // clientSecret は再発行しない（既存は払い手本人にしか返さない・冪等経路では null）。
        assertThat(result.clientSecret()).isNull();
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("名前空間分離(R2-2): source_id 値が一致(P5 item=1 と P7 team=1)でも idempotencyKey が別なら別 escrow を作る")
    void charge_namespaceSeparatedByIdempotencyKey() {
        ConnectChargeService svc = service();
        // P5（会費・source_id=payment_item_id=1）と P7（協会請求・source_id=team_id=1）が同じ source_id 値を持つが、
        // idempotencyKey は別。R2-2 修正後は idempotencyKey で dedup するため、P7 は P5 の escrow を流用しない。
        MembershipChargeCommand p5 = new MembershipChargeCommand(
                1_000L, PAYEE_ACCOUNT_ID, "cus_p5", 100L, 1L, 55L, "idem-P5-membership");
        MembershipChargeCommand p7 = new MembershipChargeCommand(
                5_000L, PAYEE_ACCOUNT_ID, "cus_p7", 200L, 1L, 55L, "idem-P7-billing");

        // どちらの idempotencyKey でも既存 escrow は無い（=別取引）。
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-P5-membership"))
                .willReturn(Optional.empty());
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-P7-billing"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_p5", "cs_p5", "requires_confirmation"))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_p7", "cs_p7", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(p5);
        svc.charge(p7);

        // 2 回とも PI を作成し（流用しない）、2 行を保存する（別 escrow）。
        verify(stripePaymentProvider, org.mockito.Mockito.times(2)).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<EscrowTransactionEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getStripeIdempotencyKey()).isEqualTo("idem-P5-membership");
        assertThat(saved.get(0).getFaceAmount()).isEqualTo(1_000L);
        assertThat(saved.get(1).getStripeIdempotencyKey()).isEqualTo("idem-P7-billing");
        assertThat(saved.get(1).getFaceAmount()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("R2-1: confirmImmediately=true は createAndConfirmDestinationPaymentIntent(PM 付き) を呼ぶ")
    void charge_confirmImmediately_offSession() {
        ConnectChargeService svc = service();
        MembershipChargeCommand cmd = new MembershipChargeCommand(
                10_000L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, "idem-membership-777", null, "pm_saved", true);
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createAndConfirmDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_mem", null, "succeeded"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(cmd);

        // off-session 確定メソッドを PM・AUTOMATIC・idempotencyKey 付きで呼ぶ。
        verify(stripePaymentProvider).createAndConfirmDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.AUTOMATIC), eq("pm_saved"), eq("idem-membership-777"));
        // 未 confirm の作成メソッドは呼ばない（off-session 経路では使わない）。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("R2-1: confirmImmediately=true で paymentMethodId が空→IllegalArgumentException（契約違反・PI never）")
    void charge_confirmImmediately_missingPm_rejected() {
        ConnectChargeService svc = service();
        MembershipChargeCommand bad = new MembershipChargeCommand(
                10_000L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, "idem-membership-777", null, null, true);
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));

        assertThatThrownBy(() -> svc.charge(bad)).isInstanceOf(IllegalArgumentException.class);

        verify(stripePaymentProvider, never()).createAndConfirmDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString(), anyString());
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("ledger 二重記帳しない: charge() は LedgerEntryRepository を一切呼ばない（複式記帳は succeeded webhook 委譲）")
    void charge_doesNotWriteLedger() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_mem", "pi_mem_secret", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        svc.charge(command());

        // charge() 時点では複式記帳しない（CAPTURE/TRANSFER_OUT/FEE は payment_intent.succeeded webhook が起票）。
        verify(ledgerEntryRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
        // capture（手動払出）も呼ばない（即時モードは Stripe の AUTOMATIC capture に委ねる）。
        verify(stripePaymentProvider, never()).captureManualPaymentIntent(anyString(), anyString());
    }

    @Test
    @DisplayName("受領 Connect 口座が未解決→404秘匿(PAYMENT_C002)・PI never")
    void charge_payeeAccountMissing_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByStripeIdempotencyKey("idem-membership-777"))
                .willReturn(Optional.empty());
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.charge(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("faceAmount 非正→IllegalArgumentException（決済不能・口座解決も PI 作成もしない）")
    void charge_nonPositiveFace_rejected() {
        ConnectChargeService svc = service();
        MembershipChargeCommand bad = new MembershipChargeCommand(
                0L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, "idem-membership-777");

        assertThatThrownBy(() -> svc.charge(bad)).isInstanceOf(IllegalArgumentException.class);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("🟡1 idempotencyKey=null→IllegalArgumentException（契約違反・dedup 不能・口座解決も PI 作成もしない）")
    void charge_nullIdempotencyKey_rejected() {
        ConnectChargeService svc = service();
        // null idempotencyKey は契約違反（escrow 二重起票防止が効かなくなる）。
        MembershipChargeCommand bad = new MembershipChargeCommand(
                10_000L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, null);

        assertThatThrownBy(() -> svc.charge(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("🟡1 idempotencyKey 空白→IllegalArgumentException（blank も契約違反）")
    void charge_blankIdempotencyKey_rejected() {
        ConnectChargeService svc = service();
        MembershipChargeCommand bad = new MembershipChargeCommand(
                10_000L, PAYEE_ACCOUNT_ID, "cus_payer", 999L, 777L, 55L, "   ");

        assertThatThrownBy(() -> svc.charge(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }
}
