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
 * F22.1 統一決済 P2-b: {@link ConnectChargeService#authorize}（謝礼の与信）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。
 * 検証: 手数料連動 / HELD 分岐（PI 未作成）/ AUTHORIZED 経路+clientSecret / 認可IDOR / 冪等。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService.authorize 単体テスト（与信）")
class ConnectChargeServiceTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;

    // PaymentFeeCalculator は純粋関数。実体を使い手数料式の一元利用を検証する。
    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService);
    }

    private ConnectAccountEntity payeeAccount(boolean payoutsEnabled) {
        return ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(10L)
                .stripeAccountId("acct_payee")
                .onboardingStatus(payoutsEnabled ? OnboardingStatus.READY : OnboardingStatus.ONBOARDING)
                .chargesEnabled(payoutsEnabled)
                .payoutsEnabled(payoutsEnabled)
                .country("JP")
                .defaultCurrency("JPY")
                .build();
    }

    private AuthorizeChargeCommand teamCommand(Long actorUserId) {
        return new AuthorizeChargeCommand(
                EscrowSourceKind.RECRUITMENT, 100L, 200L,
                ScopeKind.USER, 999L, "cus_payer",
                ScopeKind.TEAM, 10L,
                10_000L, "JPY", null, actorUserId);
    }

    @Test
    @DisplayName("手数料連動: 額面10,000→charge10,250/appFee500 を PI 作成へ渡し escrow へ記録")
    void feeLinkage_authorized() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 10L))
                .willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_abc", "pi_abc_secret", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthorizeChargeResult result = svc.authorize(teamCommand(null));

        // PaymentFeeCalculator 経由の額が Stripe へ渡る（再実装でなく一元利用）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.MANUAL), eq("escrow-100-200"));

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(saved.getFaceAmount()).isEqualTo(10_000L);
        assertThat(saved.getAmount()).isEqualTo(10_250L);
        assertThat(saved.getApplicationFeeAmount()).isEqualTo(500L);
        assertThat(saved.getCaptureMode()).isEqualTo(EscrowCaptureMode.MANUAL);
        assertThat(saved.getPayeeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(saved.getStripePaymentIntentId()).isEqualTo("pi_abc");

        // clientSecret は支払者本人へ返す。
        assertThat(result.status()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(result.clientSecret()).isEqualTo("pi_abc_secret");
        assertThat(result.chargeAmount()).isEqualTo(10_250L);
        assertThat(result.applicationFeeAmount()).isEqualTo(500L);
    }

    @Test
    @DisplayName("受取側未完(payouts_enabled=false)→ HELD・PaymentIntent never・clientSecret null")
    void payeeNotReady_held() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 10L))
                .willReturn(Optional.of(payeeAccount(false)));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthorizeChargeResult result = svc.authorize(teamCommand(null));

        // PI は一切作らない（HELD 分岐）。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(saved.getStripePaymentIntentId()).isNull();
        assertThat(saved.getHoldExpiresAt()).isNotNull();
        // 額面・手数料は HELD でも記録する。
        assertThat(saved.getFaceAmount()).isEqualTo(10_000L);
        assertThat(saved.getAmount()).isEqualTo(10_250L);
        assertThat(saved.getApplicationFeeAmount()).isEqualTo(500L);

        assertThat(result.status()).isEqualTo(EscrowStatus.HELD);
        assertThat(result.clientSecret()).isNull();
        assertThat(result.paymentIntentId()).isNull();
    }

    @Test
    @DisplayName("認可/IDOR: 明示APIで非権限actor→403・PaymentIntent never・escrow 未保存")
    void idor_nonAdminActorRejected() {
        ConnectChargeService svc = service();
        // 非権限 → AccessControlService が拒否（BusinessException）。
        org.mockito.BDDMockito.willThrow(new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN))
                .given(accessControlService).checkPermission(eq(777L), eq(10L), anyString(), anyString());

        assertThatThrownBy(() -> svc.authorize(teamCommand(777L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("冪等: 同一応募の二重与信→既存を返し PI 再作成も escrow 再保存もしない")
    void idempotent_existingReturned() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity existing = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(EscrowStatus.AUTHORIZED)
                .stripePaymentIntentId("pi_existing")
                .build();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.of(existing));

        AuthorizeChargeResult result = svc.authorize(teamCommand(null));

        assertThat(result.status()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(result.paymentIntentId()).isEqualTo("pi_existing");
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("受取側 Connect 口座が未解決→404秘匿（PAYMENT_C002）・PaymentIntent never")
    void payeeAccountMissing_notFound() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 10L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.authorize(teamCommand(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }
}
