package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.event.ChargeCaptureFailedEvent;
import com.mannschaft.app.recruitment.event.MarketListingFinalizedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-c 第一波: {@link MarketChargeCaptureListener}（最終認証→払出フック）単体テスト。
 *
 * <p>謝礼札の最終認証→AUTHORIZED escrow を capture 呼び出し / 謝礼なし札→呼ばない / HELD はスキップ。
 * 疎結合（イベント駆動）の配線を検証する。Stripe 実通信は ConnectChargeService モックで遮断する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketChargeCaptureListener 単体テスト（最終認証→払出フック）")
class MarketChargeCaptureListenerTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private MarketChargeCaptureListener listener;

    private EscrowTransactionEntity escrow(EscrowStatus status, UUID id) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(id);
        return e;
    }

    @Test
    @DisplayName("謝礼札の最終認証→AUTHORIZED escrow を capture（疎結合・イベント駆動）")
    void paymentEnabled_capturesAuthorized() {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-000000000001");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(escrow(EscrowStatus.AUTHORIZED, escrowId)));

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService).capture(escrowId);
    }

    @Test
    @DisplayName("謝礼なし札（payment_enabled=false）→capture を呼ばない")
    void paymentDisabled_noCapture() {
        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, false));

        verify(escrowTransactionRepository, never()).findBySourceKindAndSourceId(any(), any());
        verify(connectChargeService, never()).capture(any());
    }

    @Test
    @DisplayName("HELD escrow（onboarding 未完・payout 不能）はスキップして capture を呼ばない")
    void heldEscrow_skipped() {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-000000000002");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(escrow(EscrowStatus.HELD, escrowId)));

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService, never()).capture(any());
    }

    @Test
    @DisplayName("escrow 未登録（与信未成立）→capture を呼ばない（落ちない）")
    void noEscrow_noCapture() {
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of());

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService, never()).capture(any());
    }

    @Test
    @DisplayName("複数応募の札→AUTHORIZED の escrow をそれぞれ capture（HELD は除外）")
    void multipleEscrows_capturesAuthorizedOnly() {
        UUID a1 = UUID.fromString("019607a0-0000-7000-8000-000000000011");
        UUID a2 = UUID.fromString("019607a0-0000-7000-8000-000000000012");
        UUID held = UUID.fromString("019607a0-0000-7000-8000-000000000013");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(
                        escrow(EscrowStatus.AUTHORIZED, a1),
                        escrow(EscrowStatus.HELD, held),
                        escrow(EscrowStatus.AUTHORIZED, a2)));

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService).capture(a1);
        verify(connectChargeService).capture(a2);
        verify(connectChargeService, never()).capture(held);
    }

    private EscrowTransactionEntity deferredEscrow(UUID id) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(EscrowStatus.DEFERRED)
                .build();
        e.setId(id);
        return e;
    }

    @Test
    @DisplayName("7日超 fallback: DEFERRED escrow の最終認証→chargeDeferred（即時払い）を呼び capture は呼ばない")
    void deferredEscrow_chargesDeferred() {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-0000000000d1");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(deferredEscrow(escrowId)));

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService).chargeDeferred(escrowId);
        verify(connectChargeService, never()).capture(any());
    }

    @Test
    @DisplayName("混在: AUTHORIZED は capture・DEFERRED は chargeDeferred・PENDING_CONFIRMATION はスキップ")
    void mixedEscrows_routedByStatus() {
        UUID auth = UUID.fromString("019607a0-0000-7000-8000-0000000000d2");
        UUID def = UUID.fromString("019607a0-0000-7000-8000-0000000000d3");
        UUID pending = UUID.fromString("019607a0-0000-7000-8000-0000000000d4");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(
                        escrow(EscrowStatus.AUTHORIZED, auth),
                        deferredEscrow(def),
                        escrow(EscrowStatus.PENDING_CONFIRMATION, pending)));

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService).capture(auth);
        verify(connectChargeService).chargeDeferred(def);
        verify(connectChargeService, never()).capture(def);
        verify(connectChargeService, never()).chargeDeferred(auth);
        verify(connectChargeService, never()).capture(pending);
        verify(connectChargeService, never()).chargeDeferred(pending);
    }

    @Test
    @DisplayName("chargeDeferred 失敗→握り潰さず ChargeCaptureFailedEvent 発火（確定は巻き戻せない）")
    void chargeDeferredFails_publishesFailureEvent() {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-0000000000d5");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(deferredEscrow(escrowId)));
        willThrow(new RuntimeException("stripe deferred charge failed"))
                .given(connectChargeService).chargeDeferred(escrowId);

        assertThatCode(() -> listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ChargeCaptureFailedEvent> captor = ArgumentCaptor.forClass(ChargeCaptureFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().escrowId()).isEqualTo(escrowId);
        assertThat(captor.getValue().reason()).contains("stripe deferred charge failed");
    }

    @Test
    @DisplayName("capture 失敗→握り潰さず ERROR ＋ ChargeCaptureFailedEvent 発火（AFTER_COMMIT ゆえ確定は巻き戻せない）")
    void captureFails_publishesFailureEventAndDoesNotThrow() {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-000000000021");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(escrow(EscrowStatus.AUTHORIZED, escrowId)));
        willThrow(new RuntimeException("stripe capture failed")).given(connectChargeService).capture(escrowId);

        // AFTER_COMMIT 後ゆえ例外を伝播させて確定（COMPLETED）を巻き戻すことはできない。握り潰さずイベントで救済する。
        assertThatCode(() -> listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ChargeCaptureFailedEvent> captor = ArgumentCaptor.forClass(ChargeCaptureFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ChargeCaptureFailedEvent failed = captor.getValue();
        assertThat(failed.escrowId()).isEqualTo(escrowId);
        assertThat(failed.sourceKind()).isEqualTo(EscrowSourceKind.RECRUITMENT);
        assertThat(failed.sourceId()).isEqualTo(100L);
        assertThat(failed.reason()).contains("stripe capture failed");
    }

    @Test
    @DisplayName("複数応募で 1 件の capture が失敗しても他 escrow の払出は継続する（独立 try/catch）")
    void oneCaptureFails_othersStillCaptured() {
        UUID failing = UUID.fromString("019607a0-0000-7000-8000-000000000031");
        UUID ok = UUID.fromString("019607a0-0000-7000-8000-000000000032");
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 100L))
                .willReturn(List.of(escrow(EscrowStatus.AUTHORIZED, failing), escrow(EscrowStatus.AUTHORIZED, ok)));
        willThrow(new RuntimeException("stripe capture failed")).given(connectChargeService).capture(failing);

        listener.onListingFinalized(new MarketListingFinalizedEvent(100L, true));

        verify(connectChargeService).capture(failing);
        verify(connectChargeService).capture(ok);
        verify(eventPublisher).publishEvent(any(ChargeCaptureFailedEvent.class));
    }
}
