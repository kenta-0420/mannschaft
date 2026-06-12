package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 謝礼決済 第二陣: {@link ConnectChargeService#getRecruitmentPaymentView} /
 * {@link ConnectChargeService#getEscrowView}（札主の決済確認・エスクロー照会）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。検証:
 * 札主本人→clientSecret＋手数料内訳（PENDING_CONFIRMATION 時 retrieve）/ AUTHORIZED→clientSecret なし /
 * HELD→受取口座登録待ち（clientSecret なし・retrieve never）/ escrow 未存在→404（準備中）/
 * 認可・IDOR・PCI 出し分け（payee ADMIN=clientSecret なし・無関係=404）/ GET が新規 authorize を呼ばない（二重与信回避）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService 決済確認/照会 単体テスト（第二陣）")
class ConnectChargePaymentViewTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private FeePolicyResolver feePolicyResolver;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final Long PAYER_USER_ID = 999L;
    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-000000000099");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository.class));
    }

    private EscrowTransactionEntity escrow(EscrowStatus status, String piId) {
        return EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(PAYER_USER_ID).payerStripeCustomerId("cus_payer")
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").feePolicyKey("DEFAULT").status(status)
                .stripePaymentIntentId(piId)
                .build();
    }

    private ConnectAccountEntity payeeTeamAccount() {
        return ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(10L)
                .stripeAccountId("acct_payee").onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(true).payoutsEnabled(true).country("JP").defaultCurrency("JPY")
                .build();
    }

    @Test
    @DisplayName("札主本人×PENDING_CONFIRMATION: clientSecret を retrieve して返す＋手数料内訳同梱・新規 authorize は呼ばない")
    void payer_pendingConfirmation_returnsClientSecret() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L))
                .willReturn(Optional.of(escrow(EscrowStatus.PENDING_CONFIRMATION, "pi_abc")));
        given(stripePaymentProvider.retrievePaymentIntentClientSecret("pi_abc"))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", "pi_abc_secret", "requires_confirmation"));

        ConnectChargeService.PaymentView view = svc.getRecruitmentPaymentView(
                EscrowSourceKind.RECRUITMENT, 100L, 200L, PAYER_USER_ID);

        assertThat(view.status()).isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(view.clientSecret()).isEqualTo("pi_abc_secret");
        assertThat(view.faceAmount()).isEqualTo(10_000L);
        assertThat(view.chargeAmount()).isEqualTo(10_250L);
        assertThat(view.applicationFeeAmount()).isEqualTo(500L);
        // 二重与信回避: GET 経路は新規 authorize（PI 作成）を一切呼ばない。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        verify(escrowTransactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("札主本人×AUTHORIZED（確認済み）: clientSecret なし・PI retrieve は呼ばない（再 confirm させない）")
    void payer_authorized_noClientSecret() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.AUTHORIZED, "pi_abc")));

        ConnectChargeService.PaymentView view = svc.getEscrowView(ESCROW_ID, PAYER_USER_ID);

        assertThat(view.status()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(view.clientSecret()).isNull();
        verify(stripePaymentProvider, never()).retrievePaymentIntentClientSecret(anyString());
    }

    @Test
    @DisplayName("札主本人×HELD（受取口座登録待ち）: clientSecret なし・PI retrieve never（PI 未作成）")
    void payer_held_noClientSecret() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.HELD, null)));

        ConnectChargeService.PaymentView view = svc.getEscrowView(ESCROW_ID, PAYER_USER_ID);

        assertThat(view.status()).isEqualTo(EscrowStatus.HELD);
        assertThat(view.clientSecret()).isNull();
        verify(stripePaymentProvider, never()).retrievePaymentIntentClientSecret(anyString());
    }

    @Test
    @DisplayName("escrow 未存在（リスナ未起票の競合）: 404（準備中）・副作用なし")
    void escrowMissing_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.getRecruitmentPaymentView(
                EscrowSourceKind.RECRUITMENT, 100L, 200L, PAYER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        verify(escrowTransactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("受取側 TEAM ADMIN: status/金額のみ・clientSecret なし（PCI 出し分け）・PI retrieve never")
    void payeeTeamAdmin_statusOnly_noClientSecret() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.PENDING_CONFIRMATION, "pi_abc")));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeTeamAccount()));
        // checkPermission が例外を投げない＝ADMIN 認可成功。

        ConnectChargeService.PaymentView view = svc.getEscrowView(ESCROW_ID, 555L);

        assertThat(view.status()).isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(view.clientSecret()).isNull();
        assertThat(view.faceAmount()).isEqualTo(10_000L);
        verify(accessControlService).checkPermission(eq(555L), eq(10L), eq("TEAM"), anyString());
        verify(stripePaymentProvider, never()).retrievePaymentIntentClientSecret(anyString());
    }

    @Test
    @DisplayName("無関係者（payer でも payee ADMIN でもない）: 404 秘匿（認可失敗も 404 へ統一）")
    void unrelated_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findById(ESCROW_ID))
                .willReturn(Optional.of(escrow(EscrowStatus.PENDING_CONFIRMATION, "pi_abc")));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeTeamAccount()));
        // 認可失敗（非 ADMIN）→ AccessControlService が CommonErrorCode で拒否。
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkPermission(eq(777L), eq(10L), eq("TEAM"), anyString());

        assertThatThrownBy(() -> svc.getEscrowView(ESCROW_ID, 777L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                // 照会の IDOR 秘匿: 認可失敗も 403 でなく 404 へ統一（存在を漏らさない）。
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        verify(stripePaymentProvider, never()).retrievePaymentIntentClientSecret(anyString());
    }
}
