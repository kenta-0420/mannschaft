package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks private EscrowWebhookService service;

    private StripePaymentProvider.EscrowWebhookEventInfo event(String id, String type) {
        return new StripePaymentProvider.EscrowWebhookEventInfo(id, type, false, "pi_abc", null);
    }

    private EscrowTransactionEntity escrow(EscrowStatus status) {
        return EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status).stripePaymentIntentId("pi_abc")
                .build();
    }

    @Test
    @DisplayName("amount_capturable_updated → AUTHORIZED 確定（PROCESSED）")
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
    @DisplayName("payment_intent.succeeded（capture）は次Phase: IGNORED（escrow 触らない）")
    void succeeded_ignoredThisPhase() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(event("evt_3", "payment_intent.succeeded"));
        given(idempotencyService.tryBegin("evt_3", "payment_intent.succeeded", false)).willReturn(true);

        service.handleWebhook("payload", "sig");

        verify(escrowTransactionRepository, never()).save(any());
        verify(idempotencyService).markProcessed("evt_3", WebhookProcessStatus.IGNORED);
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
