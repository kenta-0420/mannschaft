package com.mannschaft.app.billing.invoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link StripeInvoiceRetriever} の Stripe API 実装。
 *
 * <p><b>全ページを辿る</b>: {@code Invoice#getLines()} の 1 ページ目だけを見ると、webhook の
 * 部分 payload と同じ欠けた状態になる。{@code autoPagingIterable()} で全明細を集め、
 * {@code lines.data} をそれで置き換え {@code has_more=false} を立てたうえで
 * {@link StripeBillingPayloadParser#parseInvoiceObject} に渡す。
 * <b>写像を webhook 側と共有する</b>のが要点で、ここで独自にマッピングすると税込・税抜の導出が
 * 静かにずれる。</p>
 *
 * <p>取得失敗は握り潰さず WARN を残し {@link Optional#empty()} を返す。呼び出し元は投影を見送り
 * （fail-closed）、Stripe の再送で再試行される。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeInvoiceRetrieverImpl implements StripeInvoiceRetriever {

    private final ObjectMapper objectMapper;
    private final StripeBillingPayloadParser parser;

    @Override
    public Optional<InvoiceView> retrieve(String invoiceRef) {
        if (invoiceRef == null || invoiceRef.isBlank()) {
            return Optional.empty();
        }
        try {
            Invoice invoice = Invoice.retrieve(invoiceRef);
            if (invoice == null) {
                log.warn("F20.1 PR5: invoice を取得できませんでした（応答なし）: invoice={}", invoiceRef);
                return Optional.empty();
            }
            ObjectNode node = (ObjectNode) objectMapper.readTree(invoice.toJson());
            ArrayNode data = objectMapper.createArrayNode();
            if (invoice.getLines() != null) {
                for (InvoiceLineItem line : invoice.getLines().autoPagingIterable()) {
                    data.add(objectMapper.readTree(line.toJson()));
                }
            }
            ObjectNode lines = objectMapper.createObjectNode();
            lines.put("object", "list");
            lines.put("has_more", false);
            lines.set("data", data);
            node.set("lines", lines);
            return parser.parseInvoiceObject(objectMapper.writeValueAsString(node));
        } catch (StripeException e) {
            log.warn("F20.1 PR5: invoice の取得に失敗したため投影を見送ります（再送で再試行）: invoice={}",
                    invoiceRef, e);
            return Optional.empty();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("F20.1 PR5: 取得した invoice を解釈できないため投影を見送ります: invoice={}", invoiceRef, e);
            return Optional.empty();
        }
    }
}
