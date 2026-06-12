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
 * F22.1 第三陣-b「7日超 fallback（完了時即時払い）」: {@link ConnectChargeService} の deferred 経路 単体テスト。
 *
 * <p>test-first（red→green）。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。検証:</p>
 * <ul>
 *   <li>{@link ConnectChargeService#authorize} で {@code deferred=true} のとき、与信（PI）を作らず
 *       {@link EscrowStatus#DEFERRED}・{@link EscrowCaptureMode#AUTOMATIC} で起票する（成立時に与信しない）。</li>
 *   <li>{@code deferred=false}（7日以内/役務日不明）は従来どおり manual-capture の PI 作成＋
 *       {@link EscrowStatus#PENDING_CONFIRMATION}（既存・回帰）。</li>
 *   <li>{@link ConnectChargeService#chargeDeferred} で DEFERRED を AUTOMATIC の destination charge へフォールバック
 *       （会費 charge と同型・{@link EscrowStatus#AUTHORIZED}＋hold_expires_at=NULL）し clientSecret を返す。</li>
 *   <li>chargeDeferred は受領口座未 READY→{@code ONBOARDING_NOT_READY}、DEFERRED 以外→{@code INVALID_ESCROW_STATE}、
 *       既起票（AUTHORIZED/CAPTURED 等）→冪等 no-op。</li>
 *   <li>chargeDeferred は ledger を起票しない（複式記帳は succeeded webhook に委譲・二重記帳防止）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService 第三陣-b deferred（7日超 fallback）単体テスト")
class ConnectChargeServiceDeferredTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private FeePolicyResolver feePolicyResolver;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");
    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");

    private ConnectChargeService service() {
        org.mockito.Mockito.lenient().when(feePolicyResolver.resolve(any(), any())).thenReturn(FeePolicy.defaultPolicy());
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new com.mannschaft.app.payment.connect.PayeeScopeResolver(), feePolicyResolver);
    }

    private ConnectAccountEntity payeeAccount(boolean payoutsEnabled) {
        ConnectAccountEntity e = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(10L).organizationId(55L)
                .stripeAccountId("acct_payee")
                .onboardingStatus(payoutsEnabled ? OnboardingStatus.READY : OnboardingStatus.ONBOARDING)
                .chargesEnabled(payoutsEnabled).payoutsEnabled(payoutsEnabled)
                .country("JP").defaultCurrency("JPY")
                .build();
        e.setId(PAYEE_ACCOUNT_ID);
        return e;
    }

    private AuthorizeChargeCommand authorizeCommand(boolean deferred) {
        return new AuthorizeChargeCommand(
                EscrowSourceKind.RECRUITMENT, 100L, 200L, ScopeKind.USER, 999L, "cus_payer",
                ScopeKind.TEAM, 10L, 10_000L, "JPY", null, null, null, deferred);
    }

    private EscrowTransactionEntity deferredEscrow() {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).captureMode(EscrowCaptureMode.AUTOMATIC)
                .sourceId(100L).sourceParticipantId(200L)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L).payerStripeCustomerId("cus_payer")
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L).currency("JPY")
                .feePolicyKey("DEFAULT").status(EscrowStatus.DEFERRED)
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    // ============================ authorize: deferred 分岐 ============================

    @Test
    @DisplayName("authorize(deferred=true): 与信(PI)を作らず DEFERRED/AUTOMATIC で起票（成立時は与信しない）")
    void authorize_deferred_recordsDeferredWithoutPaymentIntent() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 10L))
                .willReturn(Optional.of(payeeAccount(true)));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthorizeChargeResult result = svc.authorize(authorizeCommand(true));

        // 成立時は与信を立てない＝PaymentIntent を一切作らない（7日超でカード与信が役務完了前に失効するため）。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(EscrowStatus.DEFERRED);
        assertThat(saved.getCaptureMode()).isEqualTo(EscrowCaptureMode.AUTOMATIC);
        assertThat(saved.getStripePaymentIntentId()).isNull();
        assertThat(saved.getFaceAmount()).isEqualTo(10_000L);
        assertThat(saved.getAmount()).isEqualTo(10_250L);
        assertThat(saved.getApplicationFeeAmount()).isEqualTo(500L);
        // clientSecret は返さない（PI 未作成）。
        assertThat(result.status()).isEqualTo(EscrowStatus.DEFERRED);
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    @DisplayName("authorize(deferred=false): 従来どおり manual-capture PI 作成＋PENDING_CONFIRMATION（回帰）")
    void authorize_notDeferred_keepsManualEscrow() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, 100L, 200L)).willReturn(Optional.empty());
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 10L))
                .willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_x", "cs_x", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthorizeChargeResult result = svc.authorize(authorizeCommand(false));

        // 7日以内/役務日不明は従来与信＝MANUAL の PI を作成し PENDING_CONFIRMATION（既存仕様の回帰）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.MANUAL), anyString());
        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(captor.getValue().getCaptureMode()).isEqualTo(EscrowCaptureMode.MANUAL);
        assertThat(result.status()).isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(result.clientSecret()).isEqualTo("cs_x");
    }

    // ============================ chargeDeferred: 即時払い ============================

    @Test
    @DisplayName("chargeDeferred: DEFERRED を AUTOMATIC destination charge へ→AUTHORIZED＋hold_expires_at=NULL・clientSecret 返却")
    void chargeDeferred_createsImmediateCharge() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(deferredEscrow()));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_def", "cs_def", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthorizeChargeResult result = svc.chargeDeferred(ESCROW_ID);

        // 会費の即時 charge と同型: AUTOMATIC capture の destination PI を作る（charge/appFee は escrow 焼付値を使う）。
        verify(stripePaymentProvider).createDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.AUTOMATIC), anyString());
        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        EscrowTransactionEntity saved = captor.getValue();
        // 第三陣バッチ非干渉のため AUTHORIZED＋hold_expires_at=NULL（会費 charge と一貫・succeeded webhook 待ち）。
        assertThat(saved.getStatus()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(saved.getStripePaymentIntentId()).isEqualTo("pi_def");
        assertThat(saved.getHoldExpiresAt()).isNull();
        assertThat(saved.getAuthorizedAt()).isNotNull();
        // 札主は第二陣の決済確認 EP で受け取る clientSecret を返す。
        assertThat(result.clientSecret()).isEqualTo("cs_def");
        assertThat(result.status()).isEqualTo(EscrowStatus.AUTHORIZED);
        // 複式記帳は succeeded webhook 委譲（chargeDeferred では起票しない・二重記帳防止）。
        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("chargeDeferred: 受領口座未 READY→ONBOARDING_NOT_READY(409)・PI never・escrow 未更新")
    void chargeDeferred_payeeNotReady_rejected() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(deferredEscrow()));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(payeeAccount(false)));

        assertThatThrownBy(() -> svc.chargeDeferred(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("chargeDeferred: DEFERRED 以外(HELD)→INVALID_ESCROW_STATE(409)・PI never")
    void chargeDeferred_notDeferred_rejected() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity held = deferredEscrow();
        held.setStatus(EscrowStatus.HELD);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(held));

        assertThatThrownBy(() -> svc.chargeDeferred(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);

        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("chargeDeferred 冪等: 既に AUTHORIZED(PI 有)→再 PI 作成せず既存 clientSecret を retrieve して返す")
    void chargeDeferred_idempotentWhenAlreadyAuthorized() {
        ConnectChargeService svc = service();
        EscrowTransactionEntity already = deferredEscrow();
        already.setStatus(EscrowStatus.AUTHORIZED);
        already.setStripePaymentIntentId("pi_already");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(already));
        given(stripePaymentProvider.retrievePaymentIntentClientSecret("pi_already"))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_already", "cs_already", "requires_confirmation"));

        AuthorizeChargeResult result = svc.chargeDeferred(ESCROW_ID);

        assertThat(result.clientSecret()).isEqualTo("cs_already");
        assertThat(result.status()).isEqualTo(EscrowStatus.AUTHORIZED);
        // 再作成しない・保存しない（冪等）。
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowTransactionRepository, never()).save(any());
    }
}
