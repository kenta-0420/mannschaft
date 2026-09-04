package com.mannschaft.app.billing.invoice;

import com.stripe.Stripe;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * F20.1 PR5 試練: Stripe Webhook の <b>実署名つき</b> 検体を組み立てる試験補助。
 *
 * <p><b>なぜモックではなく実署名なのか</b>: 投影の実装が
 * {@code StripePaymentProvider} にどの parse メソッドを足すかは未定である。provider をモックすると
 * 「差し替えたモックを通らず、検証したい経路に一度も到達しないまま別の理由で赤くなる」ため、
 * 実装が何を呼ぼうと必ず通る唯一の収束点＝<b>実 controller + 実署名検証 + 実 DB</b> を座席にする
 * （設計書 05 §4「実 {@code StripeWebhookController} の署名 fixture で契約テストする」）。</p>
 *
 * <p>{@code api_version} は SDK の {@link Stripe#API_VERSION} に合わせる。ずれると
 * {@code EventDataObjectDeserializer#getObject()} が空になり、実装が Stripe API へ
 * retrieve しに行って（ネットワーク不在で）テストの意図と無関係に落ちるため。</p>
 */
final class StripeWebhookPayloadFixture {

    private StripeWebhookPayloadFixture() {
    }

    /** {@code Stripe-Signature} ヘッダ（{@code t=<epoch>,v1=<hex hmac-sha256>}）を組み立てる。 */
    static String signature(String payload, String secret) {
        return signature(payload, secret, System.currentTimeMillis() / 1000L);
    }

    static String signature(String payload, String secret, long timestampSec) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampSec + "." + payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return "t=" + timestampSec + ",v1=" + sb;
        } catch (Exception e) {
            throw new IllegalStateException("試験用署名の生成に失敗しました", e);
        }
    }

    /** Stripe Event 封筒を組み立てる（{@code created} は現在時刻）。 */
    static String event(String eventId, String type, String dataObjectJson) {
        return event(eventId, type, dataObjectJson, System.currentTimeMillis() / 1000L);
    }

    /** Stripe Event 封筒を組み立てる（{@code created} を明示。AC-9 の単調更新検証で使う）。 */
    static String event(String eventId, String type, String dataObjectJson, long createdEpochSec) {
        return """
                {"id":"%s","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":0,"request":{"id":null,"idempotency_key":null},
                 "type":"%s","data":{"object":%s}}"""
                .formatted(eventId, Stripe.API_VERSION, createdEpochSec, type, dataObjectJson);
    }

    /**
     * Stripe Invoice の {@code data.object} を組み立てる。
     *
     * @param linesJson {@code lines.data} の要素をカンマ区切りで並べた JSON 断片
     */
    static String invoiceObject(String invoiceId, String customerRef, String subscriptionRef, String status,
                                String currency, long subtotal, long discount, long tax, long total,
                                String linesJson) {
        long periodStart = 1_767_225_600L; // 2026-01-01T00:00:00Z
        long periodEnd = 1_769_904_000L;   // 2026-02-01T00:00:00Z
        return """
                {"id":"%s","object":"invoice","customer":"%s","subscription":%s,"status":"%s",
                 "billing_reason":"subscription_cycle","currency":"%s","collection_method":"charge_automatically",
                 "subtotal":%d,"total":%d,"tax":%d,"amount_due":%d,"amount_paid":0,"amount_remaining":%d,
                 "period_start":%d,"period_end":%d,"attempt_count":0,"attempted":false,"auto_advance":true,
                 "customer_name":"請求先 太郎","customer_email":"billing-taro@example.com",
                 "customer_address":{"country":"JP","postal_code":"1000001","state":"東京都","city":"千代田区",
                                     "line1":"千代田1-1","line2":null},
                 "total_discount_amounts":[{"amount":%d,"discount":"di_fixture"}],
                 "lines":{"object":"list","has_more":false,"url":"/v1/invoices/%s/lines","data":[%s]}}"""
                .formatted(invoiceId, customerRef,
                        subscriptionRef == null ? "null" : "\"" + subscriptionRef + "\"",
                        status, currency, subtotal, total, tax, total, total,
                        periodStart, periodEnd, discount, invoiceId, linesJson);
    }

    /** Stripe Invoice line（{@code line_item}）を組み立てる。 */
    static String lineObject(String lineId, String description, long quantity, long amount,
                             long discountAmount, long taxAmount, boolean taxInclusive,
                             Integer taxRateBasisPoints) {
        String taxRates = taxRateBasisPoints == null
                ? "[]"
                : """
                  [{"id":"txr_fixture","object":"tax_rate","display_name":"消費税",
                    "percentage":%s,"inclusive":%b,"active":true,"country":"JP"}]"""
                .formatted(new java.math.BigDecimal(taxRateBasisPoints).movePointLeft(2).toPlainString(),
                        taxInclusive);
        return """
                {"id":"%s","object":"line_item","type":"subscription","currency":"jpy",
                 "description":"%s","quantity":%d,"amount":%d,"livemode":false,"proration":false,
                 "discount_amounts":[{"amount":%d,"discount":"di_fixture"}],
                 "tax_amounts":[{"amount":%d,"inclusive":%b,"taxable_amount":%d,"tax_rate":"txr_fixture"}],
                 "tax_rates":%s,
                 "period":{"start":1767225600,"end":1769904000}}"""
                .formatted(lineId, description, quantity, amount, discountAmount,
                        taxAmount, taxInclusive, amount - discountAmount, taxRates);
    }

    /** Stripe Charge（{@code charge.refunded}）の {@code data.object} を組み立てる。 */
    static String chargeObject(String chargeId, String invoiceRef, long amount, long amountRefunded,
                               String refundId, String connectedAccountRef) {
        return """
                {"id":"%s","object":"charge","amount":%d,"amount_refunded":%d,"currency":"jpy",
                 "invoice":%s,"paid":true,"refunded":%b,"status":"succeeded",
                 "payment_intent":"pi_fixture_%s",
                 %s
                 "refunds":{"object":"list","has_more":false,"url":"/v1/charges/%s/refunds","data":[
                   {"id":"%s","object":"refund","amount":%d,"currency":"jpy","status":"succeeded",
                    "reason":"requested_by_customer","created":1769904000,"charge":"%s"}]}}"""
                .formatted(chargeId, amount, amountRefunded,
                        invoiceRef == null ? "null" : "\"" + invoiceRef + "\"",
                        amountRefunded >= amount, chargeId,
                        connectedAccountRef == null ? ""
                                : "\"transfer_data\":{\"destination\":\"" + connectedAccountRef + "\"},",
                        chargeId, refundId, amountRefunded, chargeId);
    }

    /** Stripe CreditNote の {@code data.object} を組み立てる。 */
    static String creditNoteObject(String creditNoteId, String invoiceRef, long amount, String status) {
        return """
                {"id":"%s","object":"credit_note","invoice":"%s","amount":%d,"currency":"jpy",
                 "status":"%s","reason":"order_change","created":1769904000,"number":"CN-0001",
                 "customer":"cus_billing_fixture"}"""
                .formatted(creditNoteId, invoiceRef, amount, status);
    }

    /**
     * Stripe Dispute の {@code data.object} を組み立てる（{@code charge} は<b>ID 文字列</b>）。
     *
     * <p><b>これが本番の形である</b>。Stripe の {@code charge.dispute.*} は通常 {@code charge} を
     * ID 文字列で送る。Dispute 自身は {@code invoice} を持たないため、実装は charge を retrieve して
     * その {@code invoice} から対象請求書を一意に決める（「顧客の直近の請求書」などで推測しない）。</p>
     *
     * <p><b>この形は IT では検証できない</b>: オフラインの IT は Stripe API に到達できず charge を
     * 取得できない。したがって<b>この経路（ID のみ → retrieve → invoice）の検証は
     * {@code BillingInvoiceAdjustmentWebhookServiceTest} が担う</b>
     * （retrieve 経由で解決される検体と、解決できないとき投影も請求書検索もしない検体の両方）。
     * IT 側（AC-30）は代わりに {@link #disputeObjectWithExpandedCharge} を使う。</p>
     */
    static String disputeObject(String disputeId, String chargeId, long amount, String status) {
        return """
                {"id":"%s","object":"dispute","charge":"%s","amount":%d,"currency":"jpy",
                 "status":"%s","reason":"fraudulent","created":1769904000,
                 "payment_intent":"pi_fixture_%s"}"""
                .formatted(disputeId, chargeId, amount, status, chargeId);
    }

    /**
     * Stripe Dispute の {@code data.object} を組み立てる（{@code charge} を<b>展開した</b>版）。
     *
     * <p>Stripe は {@code expand} 指定や送出設定によって {@code charge} をオブジェクトで送ることがある。
     * その場合 charge の {@code invoice} が payload に載るため、Stripe API へ問い合わせずに
     * 対象請求書を一意に決められる。</p>
     *
     * <p><b>なぜ IT ではこちらを使うのか</b>: オフラインの IT は charge を retrieve できない。
     * ここで ID のみの検体を使うと「Stripe に届かないから投影されない」という、
     * 投影ロジックとは無関係な理由で赤くなり、AC-30 が測りたい
     * 「dispute 4 種が DISPUTE 行として積まれること」を測れなくなる。
     * ID のみの現実の形は {@link #disputeObject} と
     * {@code BillingInvoiceAdjustmentWebhookServiceTest} の retrieve 経路テストで担保している。</p>
     */
    static String disputeObjectWithExpandedCharge(String disputeId, String chargeId, String invoiceRef,
                                                  long amount, String status) {
        return """
                {"id":"%s","object":"dispute","amount":%d,"currency":"jpy",
                 "status":"%s","reason":"fraudulent","created":1769904000,
                 "payment_intent":"pi_fixture_%s",
                 "charge":{"id":"%s","object":"charge","invoice":"%s","currency":"jpy","amount":%d}}"""
                .formatted(disputeId, amount, status, chargeId, chargeId, invoiceRef, amount);
    }

    /** {@code customer.subscription.deleted} の {@code data.object} を組み立てる。 */
    static String subscriptionObject(String subscriptionId, String customerRef, String status,
                                     long currentPeriodEndEpochSec) {
        return """
                {"id":"%s","object":"subscription","customer":"%s","status":"%s",
                 "current_period_start":1767225600,"current_period_end":%d,
                 "cancel_at_period_end":false,"created":1767225600,"livemode":false,
                 "items":{"object":"list","has_more":false,"url":"/v1/subscription_items","data":[]}}"""
                .formatted(subscriptionId, customerRef, status, currentPeriodEndEpochSec);
    }
}
