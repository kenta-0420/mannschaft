package com.mannschaft.app.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.ChargeView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.CreditNoteView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.DisputeView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.EventEnvelope;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceLineView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.RefundView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F20.1 PR5: 署名検証済みの生 payload から billing 投影に必要な項目を取り出すパーサ。
 *
 * <p><b>署名検証との関係</b>: 本パーサは検証を行わない。呼び出し元
 * （{@code StripeWebhookService} → {@code BillingSubscriptionWebhookService} 等）が
 * {@code Webhook.constructEvent} で署名検証を通したあとの payload だけを渡す契約である。
 * 検証前の payload を渡してはならない。</p>
 *
 * <p><b>なぜ SDK のモデルを使わないのか</b>: Stripe SDK の {@code Invoice} は
 * {@code EventDataObjectDeserializer} の API version 一致を前提とし、ずれると
 * {@code getObject()} が空になって Stripe API へ retrieve しに行く。投影は
 * 「webhook に載っている値」だけを正本にしたいので、SDK の再取得経路を持たない
 * 素の JSON 読み取りにする（設計書 05 §4）。</p>
 */
@Slf4j
@Component
public class StripeBillingPayloadParser {

    private final ObjectMapper objectMapper;

    public StripeBillingPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** payload の SHA-256（小文字 hex 64 桁）。raw payload は永続化せずこれだけを残す（AC-12）。 */
    public String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JRE 必須アルゴリズムのため到達しない。到達したら環境異常なので隠さず落とす。
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /** Event 封筒を読む。読めなければ {@link Optional#empty()}（呼び出し元が所有判定を諦める）。 */
    public Optional<EventEnvelope> parseEnvelope(String payload) {
        return root(payload).map(root -> new EventEnvelope(
                text(root, "id"),
                text(root, "type"),
                root.path("livemode").asBoolean(false),
                root.path("created").asLong(0L)));
    }

    /** {@code data.object} が invoice のときだけ {@link InvoiceView} を返す。 */
    public Optional<InvoiceView> parseInvoice(String payload) {
        return dataObject(payload, "invoice").map(this::toInvoice);
    }

    /** {@code data.object} が charge のときだけ {@link ChargeView} を返す。 */
    public Optional<ChargeView> parseCharge(String payload) {
        return dataObject(payload, "charge").map(node -> {
            List<RefundView> refunds = new ArrayList<>();
            for (JsonNode r : node.path("refunds").path("data")) {
                refunds.add(new RefundView(text(r, "id"), r.path("amount").asLong(0L),
                        text(r, "status"), text(r, "reason"), longOrNull(r, "created")));
            }
            // Connect 由来（connected account への送金指定・on_behalf_of・source_transfer）は
            // billing（プラットフォーム受取）の投影対象ではない（AC-33）。
            boolean connectOwned = !node.path("transfer_data").isMissingNode()
                    && !node.path("transfer_data").isNull()
                    || !node.path("on_behalf_of").isMissingNode() && !node.path("on_behalf_of").isNull()
                    || !node.path("source_transfer").isMissingNode() && !node.path("source_transfer").isNull();
            return new ChargeView(text(node, "id"), text(node, "invoice"),
                    node.path("amount").asLong(0L), node.path("amount_refunded").asLong(0L),
                    connectOwned, refunds);
        });
    }

    /** {@code data.object} が credit_note のときだけ {@link CreditNoteView} を返す。 */
    public Optional<CreditNoteView> parseCreditNote(String payload) {
        return dataObject(payload, "credit_note").map(node -> new CreditNoteView(
                text(node, "id"), text(node, "invoice"), node.path("amount").asLong(0L),
                text(node, "status"), text(node, "reason"), longOrNull(node, "created")));
    }

    /** {@code data.object} が dispute のときだけ {@link DisputeView} を返す。 */
    public Optional<DisputeView> parseDispute(String payload) {
        return dataObject(payload, "dispute").map(node -> new DisputeView(
                text(node, "id"), text(node, "charge"), node.path("amount").asLong(0L),
                text(node, "status"), text(node, "reason"), longOrNull(node, "created")));
    }

    // ───────────── 内部 ─────────────

    private InvoiceView toInvoice(JsonNode node) {
        List<InvoiceLineView> lines = new ArrayList<>();
        for (JsonNode l : node.path("lines").path("data")) {
            lines.add(toLine(l));
        }
        JsonNode address = node.path("customer_address");
        String addressJson = address.isMissingNode() || address.isNull() ? null : address.toString();
        return new InvoiceView(
                text(node, "id"),
                text(node, "customer"),
                text(node, "subscription"),
                text(node, "status"),
                text(node, "billing_reason"),
                text(node, "currency"),
                node.path("subtotal").asLong(0L),
                sumAmounts(node.path("total_discount_amounts")),
                node.path("tax").asLong(0L),
                node.path("total").asLong(0L),
                longOrNull(node, "period_start"),
                longOrNull(node, "period_end"),
                text(node, "customer_name"),
                text(node, "customer_email"),
                addressJson,
                lines);
    }

    private InvoiceLineView toLine(JsonNode l) {
        JsonNode taxAmounts = l.path("tax_amounts");
        long taxAmount = sumAmounts(taxAmounts);
        boolean inclusive = taxAmounts.isArray() && !taxAmounts.isEmpty()
                && taxAmounts.get(0).path("inclusive").asBoolean(false);

        Integer basisPoints = null;
        String taxName = null;
        JsonNode taxRates = l.path("tax_rates");
        if (taxRates.isArray() && !taxRates.isEmpty()) {
            JsonNode rate = taxRates.get(0);
            String percentage = text(rate, "percentage");
            if (percentage != null) {
                // percentage は「10.00」のような百分率。basis points は 100 倍（10% = 1000bp）。
                basisPoints = new BigDecimal(percentage).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
                        .intValueExact();
            }
            taxName = text(rate, "display_name");
        }

        BigDecimal quantity = new BigDecimal(l.path("quantity").asLong(1L)).setScale(3, java.math.RoundingMode.UNNECESSARY);
        return new InvoiceLineView(
                text(l, "id"),
                text(l, "description"),
                quantity,
                l.path("amount").asLong(0L),
                sumAmounts(l.path("discount_amounts")),
                taxAmount,
                inclusive,
                basisPoints,
                taxName,
                l.path("price").path("id").isMissingNode() ? null : text(l.path("price"), "id"),
                longOrNull(l.path("period"), "start"),
                longOrNull(l.path("period"), "end"));
    }

    private long sumAmounts(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return 0L;
        }
        long sum = 0L;
        for (JsonNode n : arrayNode) {
            sum += n.path("amount").asLong(0L);
        }
        return sum;
    }

    private Optional<JsonNode> dataObject(String payload, String expectedObjectType) {
        return root(payload)
                .map(root -> root.path("data").path("object"))
                .filter(node -> node.isObject() && expectedObjectType.equals(text(node, "object")));
    }

    private Optional<JsonNode> root(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (Exception e) {
            // 署名検証を通った payload が JSON として読めないのは異常。握り潰さず WARN を残し、
            // 呼び出し元には「所有判定不能」として伝える（＝確定させない）。
            log.warn("F20.1 PR5: webhook payload を JSON として解釈できませんでした", e);
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asLong();
    }
}
