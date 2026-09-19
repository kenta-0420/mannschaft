package com.mannschaft.app.billing.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mannschaft.app.billing.BillingProductKind;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Billing Center PR6b-1 AC-17: 事前見積り要求から<b>価格の同定に関わる項目を client が送れない</b>ことを
 * 機械的に強制する deserializer。
 *
 * <h2>なぜ専用 deserializer が要るか</h2>
 * <p>Spring Boot は既定で {@code spring.jackson.deserialization.fail-on-unknown-properties=false} を
 * 敷いており、未知のプロパティは<b>黙って捨てられる</b>。DTO に該当フィールドを持たせないだけでは、
 * 攻撃者が {@code priceBandVersionId} を送っても 201 が返り、「送った事実」そのものが観測できない。
 * さらに {@code @JsonIgnoreProperties(ignoreUnknown = false)} は既定値の明示にすぎず、
 * mapper 側の設定が off のときに失敗へ<b>戻す力を持たない</b>（実測済み）。</p>
 *
 * <p>そこで本 deserializer が、既知の3項目以外のキーを見つけた時点で
 * {@link UnrecognizedPropertyException} を投げる。Spring MVC はこれを
 * {@code HttpMessageNotReadableException} に包み、{@code GlobalExceptionHandler} が 400 を返す。
 * 価格・band の同定は server が tx 内で行う以上、client が指定を試みた要求は受理してはならない。</p>
 */
public class BillingChangePreviewRequestDeserializer extends JsonDeserializer<BillingChangePreviewRequest> {

    /** 受理する項目（これ以外は 400）。 */
    private static final Set<String> KNOWN_PROPERTIES =
            Set.of("toProductKind", "toProductKey", "version");

    @Override
    public BillingChangePreviewRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || !node.isObject()) {
            throw UnrecognizedPropertyException.from(parser, BillingChangePreviewRequest.class,
                    String.valueOf(node), List.copyOf(KNOWN_PROPERTIES));
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!KNOWN_PROPERTIES.contains(fieldName)) {
                throw UnrecognizedPropertyException.from(parser, BillingChangePreviewRequest.class,
                        fieldName, List.copyOf(KNOWN_PROPERTIES));
            }
        }
        return new BillingChangePreviewRequest(
                readProductKind(parser, node.get("toProductKind")),
                readText(node.get("toProductKey")),
                readVersion(node.get("version")));
    }

    private BillingProductKind readProductKind(JsonParser parser, JsonNode value) throws IOException {
        if (value == null || value.isNull()) {
            // @NotNull のバリデーションで 400 に倒す（ここで例外にすると原因が分かりにくい）。
            return null;
        }
        try {
            return BillingProductKind.valueOf(value.asText());
        } catch (IllegalArgumentException e) {
            throw UnrecognizedPropertyException.from(parser, BillingChangePreviewRequest.class,
                    "toProductKind", java.util.Arrays.stream(BillingProductKind.values())
                            .map(kind -> (Object) kind.name()).toList());
        }
    }

    private String readText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long readVersion(JsonNode value) {
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.asLong();
    }
}
