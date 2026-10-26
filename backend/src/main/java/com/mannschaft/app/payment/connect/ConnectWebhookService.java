package com.mannschaft.app.payment.connect;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F22.1 謝礼決済: Connect Webhook 受信サービス（設計書 02 §4）。
 *
 * <p>署名検証（別シークレット）→ {@code event_id} 冪等ゲート → ハンドラ実行 の順で処理する。
 * 同一 {@code event_id} の二重受信は冪等ゲートで 1 回だけ処理する（再処理しない）。</p>
 *
 * <p>本 Phase は {@code account.updated} / {@code account.application.deauthorized} を扱う。
 * payment_intent 系（与信/払出）は P2-b/c。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectWebhookService {

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final ConnectAccountService connectAccountService;

    /**
     * Connect Webhook を処理する。
     *
     * <p>署名検証に失敗した場合は {@link ConnectPaymentErrorCode#WEBHOOK_SIGNATURE_INVALID}
     * （400・設計書 02 §7 / 03 §2）を投げる。冪等ゲートで確定済み（真の重複）と判定した場合は
     * no-op で正常終了する。</p>
     *
     * <p><b>失敗の握り潰し禁止（恒久 no-op の根治）:</b> {@link #dispatch} が例外を投げた場合は
     * 受信記録を {@code FAILED} に確定（{@code REQUIRES_NEW} で独立コミット）したうえで例外を
     * <b>再送出</b>する。これにより Controller が非 200 を返し、Stripe が at-least-once 再送する。
     * 再送時は冪等ゲートが {@code FAILED} を「再処理可」と判定するため処理が回復する。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     */
    public void handleWebhook(String payload, String sigHeader) {
        StripePaymentProvider.ConnectWebhookEventInfo event;
        try {
            event = stripePaymentProvider.constructConnectEvent(payload, sigHeader);
        } catch (BusinessException e) {
            // 署名検証失敗は設計コードへ正規化して再送（400）。症状は握り潰さない。
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID, e);
        }

        // 冪等ゲート: 新規受信・未確定（RECEIVED/FAILED）は処理。確定済み（PROCESSED/IGNORED）は no-op（02 §4.1）
        boolean shouldProcess =
                idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return;
        }

        WebhookProcessStatus result;
        try {
            result = dispatch(event);
        } catch (RuntimeException e) {
            // dispatch 失敗を握り潰さない: FAILED を独立コミットで残し、例外を再送出して Stripe 再送に委ねる
            idempotencyService.markFailed(event.eventId());
            log.warn("Connect Webhook ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}, type={}",
                    event.eventId(), event.type(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
    }

    private WebhookProcessStatus dispatch(StripePaymentProvider.ConnectWebhookEventInfo event) {
        switch (event.type()) {
            case "account.updated" -> {
                connectAccountService.applyAccountUpdated(
                        event.stripeAccountId(),
                        event.chargesEnabled(),
                        event.payoutsEnabled(),
                        event.requirementsDue());
                return WebhookProcessStatus.PROCESSED;
            }
            case "account.application.deauthorized" -> {
                connectAccountService.applyDeauthorized(event.stripeAccountId());
                return WebhookProcessStatus.PROCESSED;
            }
            default -> {
                log.info("未対応の Connect Webhook イベント: type={}", event.type());
                return WebhookProcessStatus.IGNORED;
            }
        }
    }
}
