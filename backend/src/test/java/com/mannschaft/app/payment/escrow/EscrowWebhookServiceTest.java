package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.event.EscrowCapturedEvent;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-b: {@link EscrowWebhookService}（与信系 platform Webhook）単体テスト。
 *
 * <p>amount_capturable_updated→AUTHORIZED 確定 / canceled→CANCELLED / event_id 冪等 を検証する。
 * Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscrowWebhookService 単体テスト（与信 Webhook）")
class EscrowWebhookServiceTest {

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private WebhookIdempotencyService idempotencyService;
    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private EscrowWebhookService service;

    private StripePaymentProvider.EscrowWebhookEventInfo event(String id, String type) {
        return new StripePaymentProvider.EscrowWebhookEventInfo(id, type, false, "pi_abc", null, null, null, null);
    }

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

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
    @DisplayName("第一陣根治: amount_capturable_updated → PENDING_CONFIRMATION から AUTHORIZED へ昇格・authorized_at/hold_expires_at 確定（PROCESSED）")
    void amountCapturable_promotesPendingToAuthorized() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_0", "payment_intent.amount_capturable_updated"));
        given(idempotencyService.tryBegin("evt_0", "payment_intent.amount_capturable_updated", false))
                .willReturn(true);
        // authorize 直後は PENDING_CONFIRMATION（札主 confirm 待ち・authorized_at/hold_expires_at 未設定）。
        EscrowTransactionEntity pending = escrow(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(pending.getAuthorizedAt()).isNull();
        assertThat(pending.getHoldExpiresAt()).isNull();
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.of(pending));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.handleWebhook("payload", "sig");

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        // 札主 confirm 完了＝真の与信確定。capture 可能な AUTHORIZED へ昇格する。
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.AUTHORIZED);
        // hold は confirm で立つため、昇格時に authorized_at / hold_expires_at（最大7日）を刻む。
        assertThat(captor.getValue().getAuthorizedAt()).isNotNull();
        assertThat(captor.getValue().getHoldExpiresAt()).isNotNull();
        verify(idempotencyService).markProcessed("evt_0", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("amount_capturable_updated → HELD（onboarding 完了）からも AUTHORIZED 確定（PROCESSED）")
    void amountCapturable_confirmsAuthorized() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_1", "payment_intent.amount_capturable_updated"));
        given(idempotencyService.tryBegin("evt_1", "payment_intent.amount_capturable_updated", false))
                .willReturn(true);
        EscrowTransactionEntity held = escrow(EscrowStatus.HELD);
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.of(held));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.handleWebhook("payload", "sig");

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.AUTHORIZED);
        assertThat(captor.getValue().getAuthorizedAt()).isNotNull();
        verify(idempotencyService).markProcessed("evt_1", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("二重昇格（同一 event_id の amount_capturable 再送）はハンドラ非実行（冪等・行ロック前段の event_id ゲート）")
    void amountCapturable_duplicateEventNoOp() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_dup", "payment_intent.amount_capturable_updated"));
        given(idempotencyService.tryBegin("evt_dup", "payment_intent.amount_capturable_updated", false))
                .willReturn(false);

        service.handleWebhook("payload", "sig");

        verify(escrowTransactionRepository, never()).findByStripePaymentIntentId(any());
        verify(escrowTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("第一陣根治: payment_intent.payment_failed → PENDING_CONFIRMATION から CANCELLED へ遷移（PROCESSED）")
    void paymentFailed_cancelsPending() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_pf", "payment_intent.payment_failed"));
        given(idempotencyService.tryBegin("evt_pf", "payment_intent.payment_failed", false)).willReturn(true);
        EscrowTransactionEntity pending = escrow(EscrowStatus.PENDING_CONFIRMATION);
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.of(pending));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.handleWebhook("payload", "sig");

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        assertThat(captor.getValue().getCancelledAt()).isNotNull();
        verify(idempotencyService).markProcessed("evt_pf", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("二重受信（同一 event_id）の 2 回目はハンドラを実行しない（冪等）")
    void duplicateEvent_noOp() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_1", "payment_intent.amount_capturable_updated"));
        given(idempotencyService.tryBegin("evt_1", "payment_intent.amount_capturable_updated", false))
                .willReturn(false);

        service.handleWebhook("payload", "sig");

        verify(escrowTransactionRepository, never()).findByStripePaymentIntentId(any());
        verify(escrowTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("payment_intent.canceled → CANCELLED へ遷移（PROCESSED）")
    void canceled_transitionsCancelled() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_2", "payment_intent.canceled"));
        given(idempotencyService.tryBegin("evt_2", "payment_intent.canceled", false)).willReturn(true);
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.of(authorized));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.handleWebhook("payload", "sig");

        ArgumentCaptor<EscrowTransactionEntity> captor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EscrowStatus.CANCELLED);
        assertThat(captor.getValue().getCancelledAt()).isNotNull();
        verify(idempotencyService).markProcessed("evt_2", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("payment_intent.succeeded → CAPTURED 確定・ledger 借貸一致(10250=9750+500)・captured_at（PROCESSED）")
    void succeeded_confirmsCapturedAndLedger() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_3", "payment_intent.succeeded"));
        given(idempotencyService.tryBegin("evt_3", "payment_intent.succeeded", false)).willReturn(true);
        EscrowTransactionEntity authorized = escrow(EscrowStatus.AUTHORIZED);
        // 二重記帳防止のため succeeded（capture 確定）は行ロック付きで取得する。
        given(escrowTransactionRepository.findByStripePaymentIntentIdForUpdate("pi_abc")).willReturn(Optional.of(authorized));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.handleWebhook("payload", "sig");

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.CAPTURED);
        assertThat(escrowCaptor.getValue().getCapturedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
        java.util.List<LedgerEntryEntity> entries = ledgerCaptor.getValue();
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(10_250L);
        verify(idempotencyService).markProcessed("evt_3", WebhookProcessStatus.PROCESSED);

        // F08.9 P1 Wave4: CAPTURED へ新規遷移したら EscrowCapturedEvent を発火する（会費 PAID 反映トリガ）。
        ArgumentCaptor<EscrowCapturedEvent> eventCaptor = ArgumentCaptor.forClass(EscrowCapturedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().escrowTransactionId()).isEqualTo(ESCROW_ID);
        assertThat(eventCaptor.getValue().sourceKind()).isEqualTo(EscrowSourceKind.RECRUITMENT);
    }

    @Test
    @DisplayName("payment_intent.succeeded だが既に CAPTURED → no-op（ledger 二重記帳しない・PROCESSED）")
    void succeeded_alreadyCaptured_noLedgerDouble() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_6", "payment_intent.succeeded"));
        given(idempotencyService.tryBegin("evt_6", "payment_intent.succeeded", false)).willReturn(true);
        given(escrowTransactionRepository.findByStripePaymentIntentIdForUpdate("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));

        service.handleWebhook("payload", "sig");

        verify(escrowTransactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(idempotencyService).markProcessed("evt_6", WebhookProcessStatus.PROCESSED);
        // 既に CAPTURED の冪等 no-op パスでは EscrowCapturedEvent を発火しない（二重 PAID 反映を避ける）。
        verify(eventPublisher, never()).publishEvent(any(EscrowCapturedEvent.class));
    }

    @Test
    @DisplayName("根治: dispatch 失敗→FAILED 記録のうえ再送出（握り潰さない）")
    void dispatchFailure_marksFailedAndRethrows() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_4", "payment_intent.amount_capturable_updated"));
        given(idempotencyService.tryBegin("evt_4", "payment_intent.amount_capturable_updated", false))
                .willReturn(true);
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc"))
                .willThrow(new RuntimeException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(idempotencyService, times(1)).markFailed("evt_4");
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("対象 escrow 未登録（unknown PI）→ IGNORED 確定（save しない）")
    void unknownPaymentIntent_ignored() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_5", "payment_intent.canceled"));
        given(idempotencyService.tryBegin(any(), any(), anyBoolean())).willReturn(true);
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.empty());

        service.handleWebhook("payload", "sig");

        verify(escrowTransactionRepository, never()).save(any());
        verify(idempotencyService).markProcessed("evt_5", WebhookProcessStatus.IGNORED);
    }
}
