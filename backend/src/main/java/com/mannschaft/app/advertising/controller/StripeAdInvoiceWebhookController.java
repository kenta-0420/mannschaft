package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeAdInvoiceWebhookController {

    private final AdInvoiceRepository adInvoiceRepository;

    @Value("${mannschaft.stripe.webhook-secret.ad-invoices:}")
    private String webhookSecret;

    @PostMapping("/ad-invoices")
    @Transactional
    public ResponseEntity<Map<String, String>> handleInvoiceWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestBody String payload) {

        Event event;
        if (webhookSecret != null && !webhookSecret.isBlank() && stripeSignature != null) {
            try {
                event = Webhook.constructEvent(payload, stripeSignature, webhookSecret);
            } catch (SignatureVerificationException e) {
                log.warn("Stripe Webhook 署名検証失敗: {}", e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid signature"));
            }
        } else {
            log.warn("Webhook secret 未設定のため署名検証をスキップします");
            // フォールバック: Stripe SDKなしでパース（開発環境用）
            return handleWithoutSignatureVerification(payload);
        }

        String eventType = event.getType();
        log.info("Stripe Webhook 受信: type={}, id={}", eventType, event.getId());

        // Invoice オブジェクトを取得
        Invoice stripeInvoice = null;
        if (event.getDataObjectDeserializer().getObject().isPresent()) {
            Object obj = event.getDataObjectDeserializer().getObject().get();
            if (obj instanceof Invoice inv) {
                stripeInvoice = inv;
            }
        }

        if (stripeInvoice == null) {
            log.warn("Webhook から Invoice オブジェクトを取得できません: eventId={}", event.getId());
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        String stripeInvoiceId = stripeInvoice.getId();

        // 該当請求書を検索
        var invoiceOpt = adInvoiceRepository.findByStripeInvoiceId(stripeInvoiceId);
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
                LocalDateTime paidAt = stripeInvoice.getStatusTransitions() != null
                        && stripeInvoice.getStatusTransitions().getPaidAt() != null
                        ? LocalDateTime.ofInstant(Instant.ofEpochSecond(stripeInvoice.getStatusTransitions().getPaidAt()), ZoneId.of("Asia/Tokyo"))
                        : LocalDateTime.now();
                invoice.markPaid(paidAt, null);
                log.info("Stripe 入金確認: invoiceId={}", invoice.getId());
            }
            case "invoice.payment_failed" -> {
                if (invoice.getStatus() == InvoiceStatus.OVERDUE) {
                    return ResponseEntity.ok(Map.of("status", "already_processed"));
                }
                invoice.markOverdue();
                log.warn("Stripe 支払い失敗: invoiceId={}", invoice.getId());
            }
            default -> log.info("未処理の Webhook イベント: type={}", eventType);
        }

        return ResponseEntity.ok(Map.of("status", "processed"));
    }

    /**
     * 署名検証なしのフォールバック処理（開発環境用）。
     */
    private ResponseEntity<Map<String, String>> handleWithoutSignatureVerification(String payload) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = mapper.readValue(payload, Map.class);
            String eventType = (String) payloadMap.get("type");
            if (eventType == null) {
                return ResponseEntity.ok(Map.of("status", "ignored"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payloadMap.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> object = data != null ? (Map<String, Object>) data.get("object") : null;
            String stripeInvoiceId = object != null ? (String) object.get("id") : null;

            if (stripeInvoiceId == null) {
                return ResponseEntity.ok(Map.of("status", "ignored"));
            }

            var invoiceOpt = adInvoiceRepository.findByStripeInvoiceId(stripeInvoiceId);
            if (invoiceOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "not_found"));
            }

            AdInvoiceEntity invoice = invoiceOpt.get();
            switch (eventType) {
                case "invoice.paid" -> {
                    if (invoice.getStatus() != InvoiceStatus.PAID) {
                        invoice.markPaid(LocalDateTime.now(), null);
                    }
                }
                case "invoice.payment_failed" -> {
                    if (invoice.getStatus() != InvoiceStatus.OVERDUE) {
                        invoice.markOverdue();
                    }
                }
            }
            return ResponseEntity.ok(Map.of("status", "processed"));
        } catch (Exception e) {
            log.error("Webhook フォールバック処理エラー", e);
            return ResponseEntity.ok(Map.of("status", "error"));
        }
    }
}
