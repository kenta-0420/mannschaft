package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.event.AdInvoicePaidEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
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

/**
 * 広告請求書向け Stripe Webhook 受信コントローラー（F09 広告）。
 *
 * <p><b>認可根拠（{@link AuthorizedInService} クラス付与・全 1 EP が該当）</b>:
 * {@link #handleInvoiceWebhook} は本ファイル 54 行目の
 * {@code Webhook.constructEvent(payload, stripeSignature, webhookSecret)} で
 * {@code Stripe-Signature} ヘッダの HMAC 署名を検証し、失敗時は 400 を返して処理を中断する
 * （署名シークレットは {@code mannschaft.stripe.webhook-secret.ad-invoices}）。
 * 未設定時は 500 で fail-closed（本ファイル 43-46 行目）。認可根治戦役 Wave5 監査済。</p>
 */
@AuthorizedInService
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeAdInvoiceWebhookController {

    private final AdInvoiceRepository adInvoiceRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${mannschaft.stripe.webhook-secret.ad-invoices:}")
    private String webhookSecret;

    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "広告請求のStripe結果をGate状態にかかわらず反映するため")
    @PostMapping("/ad-invoices")
    @Transactional
    public ResponseEntity<Map<String, String>> handleInvoiceWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestBody String payload) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Webhook secret が未設定です。mannschaft.stripe.webhook-secret.ad-invoices を設定してください");
            return ResponseEntity.internalServerError().body(Map.of("error", "Webhook secret not configured"));
        }
        if (stripeSignature == null || stripeSignature.isBlank()) {
            log.warn("Stripe-Signature ヘッダーがありません");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing Stripe-Signature header"));
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, stripeSignature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe Webhook 署名検証失敗: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid signature"));
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
                // 運営領収書の発行契機（F08.12 §5.2）。markPaid は Webhook と手動確認の
                // 唯一の合流点だが、Webhook はこの Controller で直接呼ぶため両方から publish する。
                eventPublisher.publishEvent(new AdInvoicePaidEvent(invoice.getId()));
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

}
