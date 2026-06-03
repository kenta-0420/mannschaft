package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
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

    // PaymentFeeCalculator は純粋関数。実体を使い手数料式の一元利用を検証する。
    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new com.mannschaft.app.payment.connect.PayeeScopeResolver());
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
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of());
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
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of());
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
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of());
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
    @DisplayName("冪等: 同一 sourceId(会費項目) の二重起票→既存を返し PI 再作成も escrow 再保存もしない")
    void charge_idempotentBySourceId() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity existing = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP).sourceId(777L)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(EscrowStatus.CAPTURED)
                .stripePaymentIntentId("pi_existing")
                .build();
        UUID existingId = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
        existing.setId(existingId);
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of(existing));

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
    @DisplayName("ledger 二重記帳しない: charge() は LedgerEntryRepository を一切呼ばない（複式記帳は succeeded webhook 委譲）")
    void charge_doesNotWriteLedger() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of());
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
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 777L))
                .willReturn(List.of());
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
}
