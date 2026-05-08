package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code form_schema} JSON のバリデータ。
 *
 * <p>F09.14 設計書 §3 disclosure_form_templates および §6.4 / §6.5 に対応。
 * Jackson {@link JsonNode} のみを使い独自走査するため、外部スキーマライブラリ
 * （json-schema-validator 等）に依存しない。違反があれば
 * {@link DisclosureErrorCode#DISCLOSURE_004} の {@link BusinessException} を投げる。</p>
 *
 * <p><b>検証項目</b>:</p>
 * <ul>
 *   <li>ルートに {@code sections} 配列が必須</li>
 *   <li>各 section に {@code id}, {@code title}, {@code fields} 必須</li>
 *   <li>各 field に {@code id}, {@code label}, {@code type} 必須</li>
 *   <li>{@code type} は {@link FieldType} 列挙のいずれか</li>
 *   <li>{@code autoFillFrom} は {@link DisclosureAutoFillService#registeredKeys()} のいずれか</li>
 *   <li>ネスト深さ上限 {@value #MAX_NEST_DEPTH}</li>
 *   <li>サイズ上限 {@value #MAX_SIZE_BYTES} バイト</li>
 *   <li>section.id / field.id の重複禁止（出力先 Map 衝突防止）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DisclosureFormTemplateValidator {

    /**
     * ネスト深さの上限（設計書 §6.5 安全弁）。
     *
     * <p>「コンテナノード（object/array）の連続層数」を数える。
     * 標準的な schema {@code root → sections → element → fields → element → autoFillFilter → 値}
     * が 6 段に到達するため、autoFillFilter 内のオブジェクト 1 段を許容する余裕として 7 を採用。
     * パーサ攻撃防止が目的のため、{@value #MAX_SIZE_BYTES} バイト制限と組み合わせて
     * 過剰なネスト構造を弾く。</p>
     */
    static final int MAX_NEST_DEPTH = 7;

    /** サイズ上限（設計書 §6.5: 100KB）。 */
    static final int MAX_SIZE_BYTES = 100 * 1024;

    /**
     * 許容するフィールド型。
     */
    public enum FieldType {
        TEXT, NUMBER, DATE, SELECT, MULTISELECT, CHECKBOX, TEXTAREA, AUTO_TABLE, AUTO_FIELD
    }

    private static final Set<String> ALLOWED_TYPES;
    static {
        Set<String> s = new HashSet<>();
        for (FieldType t : FieldType.values()) {
            s.add(t.name());
        }
        ALLOWED_TYPES = Set.copyOf(s);
    }

    private final ObjectMapper objectMapper;
    private final DisclosureAutoFillService autoFillService;

    /**
     * form_schema を検証する。違反があれば {@link BusinessException} をスロー。
     *
     * @param formSchema 検証対象 JSON ルート（{@code null} 不可）
     */
    public void validate(JsonNode formSchema) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();

        if (formSchema == null || formSchema.isNull()) {
            errors.add(field("formSchema", "form_schema は必須です"));
            throw violation(errors);
        }

        // サイズチェック（再シリアライズしてバイト数を計測）
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(formSchema);
            if (bytes.length > MAX_SIZE_BYTES) {
                errors.add(field("formSchema",
                        "form_schema のサイズが上限（" + MAX_SIZE_BYTES + "バイト）を超えています: "
                                + bytes.length + "バイト"));
                throw violation(errors);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            errors.add(field("formSchema", "form_schema をシリアライズできません: " + e.getOriginalMessage()));
            throw violation(errors);
        }

        // ネスト深さチェック
        int depth = computeMaxDepth(formSchema, 1);
        if (depth > MAX_NEST_DEPTH) {
            errors.add(field("formSchema",
                    "form_schema のネストが深すぎます（上限 " + MAX_NEST_DEPTH + "）: " + depth));
        }

        if (!formSchema.isObject()) {
            errors.add(field("formSchema", "form_schema はオブジェクトでなければなりません"));
            throw violation(errors);
        }

        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            errors.add(field("sections", "sections は配列である必要があります"));
            throw violation(errors);
        }

        Set<String> sectionIds = new HashSet<>();
        Set<String> fieldIds = new HashSet<>();

        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            String sectionPath = "sections[" + i + "]";
            validateSection(section, sectionPath, sectionIds, fieldIds, errors);
        }

        if (!errors.isEmpty()) {
            throw violation(errors);
        }
    }

    private void validateSection(JsonNode section, String path,
                                 Set<String> sectionIds, Set<String> fieldIds,
                                 List<ErrorResponse.FieldError> errors) {
        if (section == null || !section.isObject()) {
            errors.add(field(path, "section はオブジェクトでなければなりません"));
            return;
        }

        String sectionId = requiredText(section, "id", path + ".id", errors);
        requiredText(section, "title", path + ".title", errors);

        if (sectionId != null && !sectionIds.add(sectionId)) {
            errors.add(field(path + ".id", "section.id が重複しています: " + sectionId));
        }

        JsonNode fields = section.get("fields");
        if (fields == null || !fields.isArray()) {
            errors.add(field(path + ".fields", "fields は配列である必要があります"));
            return;
        }

        for (int j = 0; j < fields.size(); j++) {
            JsonNode fld = fields.get(j);
            String fieldPath = path + ".fields[" + j + "]";
            validateField(fld, fieldPath, fieldIds, errors);
        }
    }

    private void validateField(JsonNode fieldNode, String path,
                               Set<String> fieldIds,
                               List<ErrorResponse.FieldError> errors) {
        if (fieldNode == null || !fieldNode.isObject()) {
            errors.add(field(path, "field はオブジェクトでなければなりません"));
            return;
        }

        String fieldId = requiredText(fieldNode, "id", path + ".id", errors);
        requiredText(fieldNode, "label", path + ".label", errors);
        String type = requiredText(fieldNode, "type", path + ".type", errors);

        if (fieldId != null && !fieldIds.add(fieldId)) {
            errors.add(field(path + ".id", "field.id が重複しています: " + fieldId));
        }

        if (type != null && !ALLOWED_TYPES.contains(type)) {
            errors.add(field(path + ".type",
                    "未知の field.type です: " + type + "（許容: " + ALLOWED_TYPES + "）"));
        }

        JsonNode autoFillFrom = fieldNode.get("autoFillFrom");
        if (autoFillFrom != null && !autoFillFrom.isNull()) {
            if (!autoFillFrom.isTextual()) {
                errors.add(field(path + ".autoFillFrom", "autoFillFrom は文字列でなければなりません"));
            } else {
                String key = autoFillFrom.asText();
                Set<String> registered = autoFillService.registeredKeys();
                if (!registered.contains(key)) {
                    errors.add(field(path + ".autoFillFrom",
                            "未知の autoFillFrom キーです: " + key
                                    + "（登録済み: " + registered + "）"));
                }
            }
        }

        JsonNode autoFillFilter = fieldNode.get("autoFillFilter");
        if (autoFillFilter != null && !autoFillFilter.isNull() && !autoFillFilter.isObject()) {
            errors.add(field(path + ".autoFillFilter", "autoFillFilter はオブジェクトでなければなりません"));
        }
    }

    /**
     * 必須テキストフィールドを取り出す。欠落時はエラーリストに追加して null を返す。
     */
    private String requiredText(JsonNode parent, String key, String path,
                                List<ErrorResponse.FieldError> errors) {
        JsonNode node = parent.get(key);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            errors.add(field(path, key + " は必須です"));
            return null;
        }
        return node.asText();
    }

    /**
     * JsonNode の「コンテナネスト深さ」を計算する。
     *
     * <p>{@link JsonNode#isContainerNode()}（object/array）に該当する層だけを数える。
     * 例えば {@code {"sections":[{"fields":[{"id":"f"}]}]}} は
     * root(1) → sections(2) → element(3) → fields(4) → field(5) で深さ 5。
     * 末端のスカラー値（文字列・数値）は深さに数えない。</p>
     */
    private int computeMaxDepth(JsonNode node, int currentDepth) {
        if (node == null || !node.isContainerNode()) {
            return currentDepth - 1; // スカラー / null は親までで打ち切り
        }
        int max = currentDepth;
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                JsonNode child = it.next().getValue();
                if (child.isContainerNode()) {
                    int d = computeMaxDepth(child, currentDepth + 1);
                    if (d > max) {
                        max = d;
                    }
                }
            }
        } else { // isArray
            for (JsonNode child : node) {
                if (child.isContainerNode()) {
                    int d = computeMaxDepth(child, currentDepth + 1);
                    if (d > max) {
                        max = d;
                    }
                }
            }
        }
        return max;
    }

    private static ErrorResponse.FieldError field(String path, String message) {
        return new ErrorResponse.FieldError(path, message);
    }

    private static BusinessException violation(List<ErrorResponse.FieldError> errors) {
        return new BusinessException(DisclosureErrorCode.DISCLOSURE_004, errors);
    }

}
