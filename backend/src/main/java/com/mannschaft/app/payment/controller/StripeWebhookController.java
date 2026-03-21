package com.mannschaft.app.payment.controller;

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
 * エンドポイント数: 1（POST webhooks/stripe）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Stripe Webhook", description = "F08.2 Stripe Webhook 受信")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService stripeWebhookService;

    /**
     * Stripe Webhook を受信する。
     * <p>
     * 署名検証には生ボディ（raw body）が必要。{@code @RequestBody String} でパース前の文字列を受け取る。
     */
    @PostMapping("/stripe")
    @Operation(summary = "Stripe Webhook 受信")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            stripeWebhookService.handleWebhook(payload, sigHeader);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Webhook 処理中にエラー。200 を返して再送を防止します: {}", e.getMessage());
            // Webhook ハンドラ内では 5xx を返さない設計
            return ResponseEntity.ok().build();
        }
    }
}
