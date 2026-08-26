package com.mannschaft.app.payment.connect;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Connect Webhook 受信サービスの単体テスト。
 *
 * <p>T1 Webhook 冪等 / T2 署名検証失敗 / T9 account.updated 鏡像更新 を検証する。
 * Stripe 実通信は {@link StripePaymentProvider} モックで遮断する（IF 越し）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectWebhookService 単体テスト（T1/T2/T9）")
class ConnectWebhookServiceTest {

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private WebhookIdempotencyService idempotencyService;
    @Mock private ConnectAccountService connectAccountService;

    @InjectMocks private ConnectWebhookService service;

    private StripePaymentProvider.ConnectWebhookEventInfo accountUpdatedEvent() {
        return new StripePaymentProvider.ConnectWebhookEventInfo(
                "evt_123", "account.updated", false,
                "acct_xxx", true, true, List.of());
    }

    @Test
    @DisplayName("T2: 署名検証失敗 → PAYMENT_C040（400）を伝播")
    void t2_signatureInvalid() {
        given(stripePaymentProvider.constructConnectEvent(any(), any()))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID));

        assertThatThrownBy(() -> service.handleWebhook("payload", "bad-sig"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);

        verify(idempotencyService, never()).tryBegin(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("T2: 署名検証で予期せぬ例外 → PAYMENT_C040 に正規化（握り潰さない）")
    void t2_signatureUnexpectedException() {
        given(stripePaymentProvider.constructConnectEvent(any(), any()))
                .willThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("T1: 新規 event_id は 1 回だけハンドラ実行し PROCESSED 確定")
    void t1_freshEventProcessedOnce() {
        StripePaymentProvider.ConnectWebhookEventInfo event = accountUpdatedEvent();
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        given(idempotencyService.tryBegin("evt_123", "account.updated", false)).willReturn(true);

        service.handleWebhook("payload", "sig");

        verify(connectAccountService, times(1))
                .applyAccountUpdated("acct_xxx", true, true, List.of());
        verify(idempotencyService).markProcessed("evt_123", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("T1: 二重受信（同一 event_id）の 2 回目はハンドラを実行しない（冪等）")
    void t1_duplicateEventNoOp() {
        StripePaymentProvider.ConnectWebhookEventInfo event = accountUpdatedEvent();
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        // 2 回目: 冪等ゲートが false（既処理）を返す
        given(idempotencyService.tryBegin("evt_123", "account.updated", false)).willReturn(false);

        service.handleWebhook("payload", "sig");

        verify(connectAccountService, never())
                .applyAccountUpdated(anyString(), anyBoolean(), anyBoolean(), anyList());
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("T9: account.updated → connect_accounts を鏡像更新")
    void t9_accountUpdatedMirrors() {
        StripePaymentProvider.ConnectWebhookEventInfo event =
                new StripePaymentProvider.ConnectWebhookEventInfo(
                        "evt_999", "account.updated", true,
                        "acct_abc", true, false, List.of("individual.verification.document"));
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        given(idempotencyService.tryBegin(eq("evt_999"), eq("account.updated"), eq(true)))
                .willReturn(true);

        service.handleWebhook("payload", "sig");

        verify(connectAccountService).applyAccountUpdated(
                "acct_abc", true, false, List.of("individual.verification.document"));
    }

    @Test
    @DisplayName("根治: dispatch 失敗 → FAILED 記録のうえ例外を再送出（握り潰さない・恒久 no-op 防止）")
    void dispatchFailureMarksFailedAndRethrows() {
        StripePaymentProvider.ConnectWebhookEventInfo event = accountUpdatedEvent();
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        given(idempotencyService.tryBegin("evt_123", "account.updated", false)).willReturn(true);
        // ハンドラが一過性障害で例外を投げる
        org.mockito.BDDMockito.willThrow(new RuntimeException("downstream down"))
                .given(connectAccountService)
                .applyAccountUpdated(anyString(), anyBoolean(), anyBoolean(), anyList());

        // 例外を握り潰さず再送出する（Controller が非200 → Stripe 再送）
        assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream down");

        // FAILED が記録され、PROCESSED は確定されない（再送時に再処理可能になる）
        verify(idempotencyService, times(1)).markFailed("evt_123");
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("根治: FAILED 記録後の再送 → tryBegin が再処理を許可（true）なら 2 回目で処理が回復")
    void retryAfterFailureReprocesses() {
        StripePaymentProvider.ConnectWebhookEventInfo event = accountUpdatedEvent();
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        // 再送時: 冪等ゲートが「再処理可」（FAILED/RECEIVED）と判定して true を返す
        given(idempotencyService.tryBegin("evt_123", "account.updated", false)).willReturn(true);

        service.handleWebhook("payload", "sig");

        verify(connectAccountService, times(1))
                .applyAccountUpdated("acct_xxx", true, true, List.of());
        verify(idempotencyService).markProcessed("evt_123", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("未対応イベントは IGNORED 確定（ハンドラ呼ばず）")
    void unknownEventIgnored() {
        StripePaymentProvider.ConnectWebhookEventInfo event =
                new StripePaymentProvider.ConnectWebhookEventInfo(
                        "evt_777", "capability.updated", false,
                        "acct_zzz", false, false, List.of());
        given(stripePaymentProvider.constructConnectEvent(any(), any())).willReturn(event);
        given(idempotencyService.tryBegin(any(), any(), anyBoolean())).willReturn(true);

        service.handleWebhook("payload", "sig");

        verify(connectAccountService, never())
                .applyAccountUpdated(anyString(), anyBoolean(), anyBoolean(), anyList());
        verify(idempotencyService).markProcessed("evt_777", WebhookProcessStatus.IGNORED);
    }
}
