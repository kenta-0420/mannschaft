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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-c 第二波: {@link EscrowWebhookService#handleChargeRefunded}（{@code charge.refunded}）単体テスト。
 *
 * <p>test-first。Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。
 * 検証: 全額返金→refunds SUCCEEDED＋escrow REFUNDED 確定 / 部分返金→PARTIALLY_REFUNDED /
 * 二重 event は 1 回（event_id 冪等）/ 既処理（SUCCEEDED 済み）は no-op / 対象なし escrow は委譲元へ
 * フォールバック可能なよう {@code false} を返す。escrow 行ロックで二重処理を防ぐ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscrowWebhookService.handleChargeRefunded 単体テスト（charge.refunded）")
class EscrowWebhookServiceRefundTest {

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private WebhookIdempotencyService idempotencyService;
    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;

    @InjectMocks private EscrowWebhookService service;

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000dd");

    private StripePaymentProvider.EscrowWebhookEventInfo refundEvent(String eventId, String refundId,
                                                                     long refundedMinor, long chargeMinor) {
        return new StripePaymentProvider.EscrowWebhookEventInfo(
                eventId, "charge.refunded", false, "pi_abc", null, refundId, refundedMinor, chargeMinor);
    }

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

    private RefundEntity pendingRefund(String stripeRefundId, long amount) {
        return RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId(stripeRefundId)
                .amount(amount).currency("JPY").reason("cancellation").status(RefundStatus.PENDING).build();
    }

    @Test
    @DisplayName("charge.refunded（全額・支払者負担モデル）→ refunds SUCCEEDED 確定・累計=transferAmount(9,750) で escrow REFUNDED 確定（true 返却・PROCESSED）")
    void fullRefund_confirmsSucceededAndRefunded() {
        // 支払者負担モデル: 全額返金の refunds.amount は transferAmount = amount(10,250) − fee(500) = 9,750。
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(refundEvent("evt_r1", "re_1", 9_750L, 10_250L));
        // 存在判定（非ロック）→ escrow あり → 冪等ゲート消費。
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(idempotencyService.tryBegin("evt_r1", "charge.refunded", false)).willReturn(true);
        // 行ロックで escrow を取得（二重処理防止）。
        given(escrowTransactionRepository.findByStripePaymentIntentIdForUpdate("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        // 同一インスタンスを両 finder が返すことで、SUCCEEDED 確定後の累計集計に反映される（実 DB 同等）。
        RefundEntity target = pendingRefund("re_1", 9_750L);
        given(refundRepository.findByStripeRefundId("re_1")).willReturn(Optional.of(target));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(List.of(target));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean handled = service.handleChargeRefunded("payload", "sig");

        assertThat(handled).isTrue();
        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.SUCCEEDED);

        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.REFUNDED);
        verify(idempotencyService).markProcessed("evt_r1", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("charge.refunded（部分）→ refunds SUCCEEDED・escrow PARTIALLY_REFUNDED（累計<face_amount）")
    void partialRefund_partiallyRefunded() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(refundEvent("evt_r2", "re_2", 3_000L, 10_250L));
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(idempotencyService.tryBegin("evt_r2", "charge.refunded", false)).willReturn(true);
        given(escrowTransactionRepository.findByStripePaymentIntentIdForUpdate("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(refundRepository.findByStripeRefundId("re_2")).willReturn(Optional.of(pendingRefund("re_2", 3_000L)));
        given(refundRepository.findByEscrowTransactionId(ESCROW_ID))
                .willReturn(List.of(pendingRefund("re_2", 3_000L)));
        given(refundRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(escrowTransactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean handled = service.handleChargeRefunded("payload", "sig");

        assertThat(handled).isTrue();
        ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
        verify(escrowTransactionRepository).save(escrowCaptor.capture());
        assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("二重受信（同一 event_id）の 2 回目はハンドラを実行しない（冪等）・true 返却")
    void duplicateEvent_noOp() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(refundEvent("evt_r1", "re_1", 10_000L, 10_250L));
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.CAPTURED)));
        given(idempotencyService.tryBegin("evt_r1", "charge.refunded", false)).willReturn(false);

        boolean handled = service.handleChargeRefunded("payload", "sig");

        assertThat(handled).isTrue();
        verify(escrowTransactionRepository, never()).findByStripePaymentIntentIdForUpdate(any());
        verify(refundRepository, never()).save(any());
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("既に SUCCEEDED 済みの refund → no-op（refunds も escrow も再保存しない・PROCESSED）")
    void alreadySucceeded_noOp() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(refundEvent("evt_r3", "re_3", 10_000L, 10_250L));
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.REFUNDED)));
        given(idempotencyService.tryBegin("evt_r3", "charge.refunded", false)).willReturn(true);
        given(escrowTransactionRepository.findByStripePaymentIntentIdForUpdate("pi_abc"))
                .willReturn(Optional.of(escrow(EscrowStatus.REFUNDED)));
        RefundEntity succeeded = RefundEntity.builder().escrowTransactionId(ESCROW_ID).stripeRefundId("re_3")
                .amount(10_000L).currency("JPY").reason("cancellation").status(RefundStatus.SUCCEEDED).build();
        given(refundRepository.findByStripeRefundId("re_3")).willReturn(Optional.of(succeeded));

        boolean handled = service.handleChargeRefunded("payload", "sig");

        assertThat(handled).isTrue();
        verify(refundRepository, never()).save(any());
        verify(escrowTransactionRepository, never()).save(any());
        verify(idempotencyService).markProcessed("evt_r3", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("対象 escrow が無い（会費等の charge.refunded）→ false 返却（委譲元へフォールバック・冪等ゲート消費せず）")
    void noEscrow_returnsFalseForFallback() {
        given(stripePaymentProvider.constructEscrowEvent(any(), any()))
                .willReturn(refundEvent("evt_r4", "re_4", 5_000L, 5_000L));
        given(escrowTransactionRepository.findByStripePaymentIntentId("pi_abc")).willReturn(Optional.empty());

        boolean handled = service.handleChargeRefunded("payload", "sig");

        assertThat(handled).isFalse();
        // フォールバック前提のため冪等ゲートは消費しない（会費側で処理されるべき）。
        verify(idempotencyService, never()).tryBegin(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(refundRepository, never()).save(any());
    }
}
