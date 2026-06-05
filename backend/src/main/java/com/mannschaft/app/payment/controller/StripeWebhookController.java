package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ConnectWebhookService;
import com.mannschaft.app.payment.service.StripeWebhookRetryableException;
import com.mannschaft.app.payment.service.StripeWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe Webhook コントローラー。Stripe からのイベント通知を受信する。
 * <p>
 * エンドポイント数: 2
 * <ul>
 *   <li>POST {@code /api/v1/webhooks/stripe} — F08.2 platform Webhook（既存）</li>
 *   <li>POST {@code /api/v1/webhooks/stripe/connect} — F22.1 Connect Webhook（別署名シークレット）</li>
 * </ul>
 * 両エンドポイントとも {@code /api/v1/webhooks/stripe/*} の permitAll で被覆済み（SecurityConfig・03 §2.1）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Stripe Webhook", description = "F08.2 / F22.1 Stripe Webhook 受信")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService stripeWebhookService;
    private final ConnectWebhookService connectWebhookService;

    /**
     * Stripe platform Webhook を受信する（F08.2）。
     * <p>
     * 署名検証には生ボディ（raw body）が必要。{@code @RequestBody String} でパース前の文字列を受け取る。
     * <p>
     * {@code Stripe-Signature} ヘッダが存在しない場合は 400（{@code PAYMENT_C040}）を返す。
     * Spring の {@code required = true}（デフォルト）のままにすると
     * {@code MissingRequestHeaderException → 500(COMMON_999)} となるため {@code required = false} に設定し、
     * メソッド冒頭で明示的に検証する。
     */
    @PostMapping("/stripe")
    @Operation(summary = "Stripe Webhook 受信（platform）")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        try {
            stripeWebhookService.handleWebhook(payload, sigHeader);
            return ResponseEntity.ok().build();
        } catch (StripeWebhookRetryableException e) {
            // F08.9 P5: 継続課金の invoice.created 固定手数料上書き失敗 等は「再送させたい失敗」。
            // 握り潰さず再送出して 5xx を返し、Stripe の at-least-once 再送（draft 窓内・指数バックオフ）で
            // リカバリさせる（設計書 02 §4.2・症状を隠さない）。F08.2 既存イベントの予期せぬ例外は下の catch で
            // 従来どおり 200 で握る（再送ストーム回避）ため、他処理への影響はない。
            log.error("Webhook 処理を再送に委ねます（retryable）。5xx を返します: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Webhook 処理中にエラー。200 を返して再送を防止します: {}", e.getMessage());
            // Webhook ハンドラ内では 5xx を返さない設計（F08.2 既存イベント）
            return ResponseEntity.ok().build();
        }
    }

    /**
     * Stripe Connect Webhook を受信する（F22.1・設計書 02 §4）。
     * <p>
     * platform とは別の署名シークレット（{@code mannschaft.stripe.connect-webhook-secret}）で検証する。
     * 署名検証失敗は {@code BusinessException}（{@code PAYMENT_C040} → 400）として伝播させ
     * {@code GlobalExceptionHandler} に委ねる（設計書 02 §4.1: 署名失敗=400）。
     * 署名が正当ならハンドラ内の業務エラーは握らず、冪等ゲートで重複は no-op となる。
     * <p>
     * {@code Stripe-Signature} ヘッダが存在しない場合は 400（{@code PAYMENT_C040}）を返す。
     * platform Webhook と同様、{@code required = false} で受け取りメソッド冒頭で明示検証する。
     */
    @PostMapping("/stripe/connect")
    @Operation(summary = "Stripe Connect Webhook 受信")
    public ResponseEntity<Void> handleConnectWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        connectWebhookService.handleWebhook(payload, sigHeader);
        return ResponseEntity.ok().build();
    }
}
