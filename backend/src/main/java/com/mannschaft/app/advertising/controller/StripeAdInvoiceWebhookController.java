package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stripe 広告請求書 Webhook コントローラー。
 * <p>
 * Stripe からの invoice.paid / invoice.payment_failed イベントを処理する。
 * JWT 認証不要（Stripe 署名検証で認可）。
 */
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeAdInvoiceWebhookController {

    private final AdInvoiceRepository adInvoiceRepository;

    /**
     * Stripe Invoice Webhook を処理する。
     * <p>
     * TODO: Stripe SDK 導入後に署名検証（Stripe-Signature ヘッダー）を実装する。
     * 現時点ではリクエストボディのパース処理のみ。
     */
    @PostMapping("/ad-invoices")
    @Transactional
    public ResponseEntity<Map<String, String>> handleInvoiceWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestBody Map<String, Object> payload) {

        // TODO: Stripe SDK で署名検証を実装
        // WebhookSignature.verifyHeader(payload, stripeSignature, endpointSecret);

        String eventType = (String) payload.get("type");
        if (eventType == null) {
            log.warn("Webhook イベントタイプが不明: {}", payload);
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> object = data != null ? (Map<String, Object>) data.get("object") : null;
        String stripeInvoiceId = object != null ? (String) object.get("id") : null;

        if (stripeInvoiceId == null) {
            log.warn("Webhook に stripe_invoice_id がありません: {}", payload);
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        // 該当請求書を検索
        var invoiceOpt = adInvoiceRepository.findAll().stream()
                .filter(inv -> stripeInvoiceId.equals(inv.getStripeInvoiceId()))
                .findFirst();
        if (invoiceOpt.isEmpty()) {
            log.info("対象の請求書が見つかりません: stripeInvoiceId={}", stripeInvoiceId);
            return ResponseEntity.ok(Map.of("status", "not_found"));
        }

        AdInvoiceEntity invoice = invoiceOpt.get();

        switch (eventType) {
            case "invoice.paid" -> {
                if (invoice.getStatus() == InvoiceStatus.PAID) {
                    log.info("重複イベント（冪等性）: invoiceId={}", invoice.getId());
                    return ResponseEntity.ok(Map.of("status", "already_processed"));
                }
                invoice.markPaid(LocalDateTime.now(), null);
                log.info("Stripe 入金確認: invoiceId={}", invoice.getId());
                // TODO: 広告主にメール通知
            }
            case "invoice.payment_failed" -> {
                invoice.markOverdue();
                log.warn("Stripe 支払い失敗: invoiceId={}", invoice.getId());
                // TODO: 広告主にメール通知、SYSTEM_ADMIN にプッシュ通知
            }
            default -> {
                log.info("未処理の Webhook イベント: type={}", eventType);
            }
        }

        return ResponseEntity.ok(Map.of("status", "processed"));
    }
}
