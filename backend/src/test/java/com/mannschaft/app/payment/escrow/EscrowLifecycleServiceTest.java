package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 第三陣: {@link EscrowLifecycleService}（未確認放置の自動取消・HELD 昇格）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。
 * 検証: PENDING_CONFIRMATION 取消（cancelAuthorization 呼び・CANCELLED・通知）/ 対象外状態 no-op /
 * HELD・AUTHORIZED 取消 / HELD 昇格（PI 作成→PENDING_CONFIRMATION・札主通知）/ 冪等 no-op / 行ロック取得。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscrowLifecycleService 単体テスト（自動取消・HELD 昇格）")
class EscrowLifecycleServiceTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private EscrowNotificationService escrowNotificationService;

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    private EscrowLifecycleService service() {
        StaticMessageSource ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return new EscrowLifecycleService(escrowTransactionRepository, connectAccountRepository,
                stripePaymentProvider, escrowNotificationService, ms);
    }

    private EscrowTransactionEntity escrow(EscrowStatus status, String piId) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payerStripeCustomerId("cus_payer")
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status).stripePaymentIntentId(piId)
                .build();
        e.setId(ESCROW_ID);
        return e;
    }

    private ConnectAccountEntity payee(boolean payoutsEnabled) {
        return ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(42L)
                .stripeAccountId("acct_payee")
                .payoutsEnabled(payoutsEnabled).chargesEnabled(true)
                .country("JP").defaultCurrency("JPY")
                .build();
    }

    // ── PENDING_CONFIRMATION 取消 ──

    @Test
    @DisplayName("PENDING_CONFIRMATION 放置→cancelAuthorization 呼び・CANCELLED・取消通知")
    void pendingConfirmation_cancelled() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.PENDING_CONFIRMATION, "pi_abc");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean result = svc.cancelExpiredPendingConfirmation(ESCROW_ID);

        assertThat(result).isTrue();
        verify(stripePaymentProvider).cancelAuthorization("pi_abc", "cancel-" + ESCROW_ID);
        assertThat(e.getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        assertThat(e.getCancelledAt()).isNotNull();
        verify(escrowNotificationService).notifyCancelled(eq(e), anyString(), anyString());
        // 行ロックで取得していること（findById ではなく findByIdForUpdate）。
        verify(escrowTransactionRepository).findByIdForUpdate(ESCROW_ID);
    }

    @Test
    @DisplayName("既 CANCELLED は no-op（cancelAuthorization never・通知 never・false）")
    void pendingConfirmation_alreadyCancelled_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.CANCELLED, "pi_abc");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));

        boolean result = svc.cancelExpiredPendingConfirmation(ESCROW_ID);

        assertThat(result).isFalse();
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
        verify(escrowNotificationService, never()).notifyCancelled(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("AUTHORIZED へ confirm 済みは PENDING 取消対象外（no-op）")
    void pendingConfirmation_alreadyAuthorized_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.AUTHORIZED, "pi_abc");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));

        assertThat(svc.cancelExpiredPendingConfirmation(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
    }

    @Test
    @DisplayName("対象 escrow 不在は no-op（false）")
    void pendingConfirmation_notFound_noop() {
        EscrowLifecycleService svc = service();
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.empty());

        assertThat(svc.cancelExpiredPendingConfirmation(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
    }

    // ── HELD/AUTHORIZED hold 失効取消 ──

    @Test
    @DisplayName("HELD（PI 未作成）失効→Stripe 呼ばず CANCELLED・取消通知")
    void held_cancelled_withoutStripe() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.HELD, null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean result = svc.cancelExpiredHeldOrAuthorized(ESCROW_ID);

        assertThat(result).isTrue();
        // PI 未作成のため Stripe cancel は呼ばない（capture 前ゆえ課金なし）。
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
        assertThat(e.getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        verify(escrowNotificationService).notifyCancelled(eq(e), anyString(), anyString());
    }

    @Test
    @DisplayName("AUTHORIZED（PI あり）失効→cancelAuthorization 呼び CANCELLED・取消通知")
    void authorized_cancelled_withStripe() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.AUTHORIZED, "pi_xyz");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean result = svc.cancelExpiredHeldOrAuthorized(ESCROW_ID);

        assertThat(result).isTrue();
        verify(stripePaymentProvider).cancelAuthorization("pi_xyz", "cancel-" + ESCROW_ID);
        assertThat(e.getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        verify(escrowNotificationService).notifyCancelled(eq(e), anyString(), anyString());
    }

    @Test
    @DisplayName("CAPTURED は hold 失効取消対象外（no-op）")
    void captured_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.CAPTURED, "pi_abc");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));

        assertThat(svc.cancelExpiredHeldOrAuthorized(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).cancelAuthorization(anyString(), anyString());
    }

    // ── HELD 昇格 ──

    @Test
    @DisplayName("HELD 昇格: PI 作成→PENDING_CONFIRMATION・札主決済確認通知")
    void promoteHeld_createsPiAndNotifies() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.HELD, null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(connectAccountRepository.findById(e.getPayeeConnectAccountId()))
                .willReturn(Optional.of(payee(true)));
        given(stripePaymentProvider.createDestinationPaymentIntent(
                eq(10_250L), eq("JPY"), eq("cus_payer"), eq(500L), eq("acct_payee"),
                eq(CaptureMethod.MANUAL), eq("escrow-100-200")))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_new", "cs_new", "requires_confirmation"));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean result = svc.promoteHeldEscrow(ESCROW_ID);

        assertThat(result).isTrue();
        assertThat(e.getStatus()).isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(e.getStripePaymentIntentId()).isEqualTo("pi_new");
        verify(escrowNotificationService).notifyPaymentRequired(eq(e), anyString(), anyString());
        verify(escrowTransactionRepository).findByIdForUpdate(ESCROW_ID);
    }

    @Test
    @DisplayName("既昇格（PI 設定済 or 非 HELD）は no-op（PI 作成 never・通知 never）")
    void promoteHeld_alreadyPromoted_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.PENDING_CONFIRMATION, "pi_existing");
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));

        assertThat(svc.promoteHeldEscrow(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(escrowNotificationService, never()).notifyPaymentRequired(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("HELD だが payee 未 READY（payouts_enabled=false）は据え置き（no-op・PI never）")
    void promoteHeld_payeeNotReady_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.HELD, null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(connectAccountRepository.findById(e.getPayeeConnectAccountId()))
                .willReturn(Optional.of(payee(false)));

        assertThat(svc.promoteHeldEscrow(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("HELD だが札主 Stripe Customer 未解決は据え置き（no-op・PI never・症状を隠さない）")
    void promoteHeld_noCustomer_noop() {
        EscrowLifecycleService svc = service();
        EscrowTransactionEntity e = escrow(EscrowStatus.HELD, null);
        e.setPayerStripeCustomerId(null);
        given(escrowTransactionRepository.findByIdForUpdate(ESCROW_ID)).willReturn(Optional.of(e));
        given(connectAccountRepository.findById(e.getPayeeConnectAccountId()))
                .willReturn(Optional.of(payee(true)));

        assertThat(svc.promoteHeldEscrow(ESCROW_ID)).isFalse();
        verify(stripePaymentProvider, never()).createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        assertThat(e.getStatus()).isEqualTo(EscrowStatus.HELD);
    }
}
