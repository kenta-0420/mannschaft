package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 振り返りエントリ {@code structured_content} のサニタイズ＋バリデーション（F06.5・§2.3）。
 *
 * <p>固定 5 階層（main_theme → sections[heading → subsections[sub_heading/detail/supplement]] + free_note）の
 * JSON を受け取り、各テキストフィールドから {@code <script>}・{@code on*} 属性・{@code javascript:} 等を除去
 * （{@link HtmlSanitizer#sanitizePlainText} で純テキスト化）し、サイズ/件数/字数の上限を検証する。
 * 表示時は出力エスケープと併せた XSS 二重防御（F06.1/F06.4 と同方針）。</p>
 *
 * <p>違反時は {@link ReflectionErrorCode#REFLECTION_CONTENT_INVALID}（400）を送出する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionContentSanitizer {

    private final ObjectMapper objectMapper;

    /**
     * structured_content をサニタイズ＋検証し、永続化用 JSON 文字列を返す。
     *
     * @param content 入力 JSON（§2.3 スキーマ）
     * @return サニタイズ済みの JSON 文字列（DB 保存用）
     * @throws BusinessException 上限超過・スキーマ不正（{@link ReflectionErrorCode#REFLECTION_CONTENT_INVALID}）
     */
    public String sanitizeAndSerialize(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        ObjectNode root = (ObjectNode) content.deepCopy();

        // main_theme（必須・字数上限）
        String mainTheme = textOf(root, "main_theme");
        if (mainTheme == null || mainTheme.isBlank()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        root.put("main_theme", sanitize(mainTheme, ReflectionConstants.MAX_HEADING_LENGTH));

        // sections（任意・最大 30）
        JsonNode sectionsNode = root.get("sections");
        if (sectionsNode != null && !sectionsNode.isNull()) {
            if (!sectionsNode.isArray()) {
                throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
            }
            ArrayNode sections = (ArrayNode) sectionsNode;
            if (sections.size() > ReflectionConstants.MAX_SECTIONS) {
                throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
            }
            for (JsonNode sectionNode : sections) {
                sanitizeSection(sectionNode);
            }
        }

        // free_note（マスク対象外・字数上限）
        String freeNote = textOf(root, "free_note");
        if (freeNote != null) {
            root.put("free_note", sanitize(freeNote, ReflectionConstants.MAX_FREE_NOTE_LENGTH));
        }

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("structured_content のシリアライズ失敗", e);
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length
                > ReflectionConstants.MAX_STRUCTURED_CONTENT_BYTES) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        return serialized;
    }

    /**
     * recall の recalled_content をサニタイズ＋シリアライズする。
     *
     * <p>recalled_content は structured_content と同形 or 自由テキスト（§2.4）。固定スキーマ検証は緩く、
     * サイズ上限と JSON 全体のテキストサニタイズ（オブジェクトなら section も）を適用する。</p>
     */
    public String sanitizeRecalledContent(JsonNode content) {
        if (content == null || content.isNull()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        // structured_content と同形ならフルサニタイズ、そうでなければテキストノードを純テキスト化。
        JsonNode sanitized = content.isObject() && content.has("main_theme")
                ? sanitizeStructuredLike(content)
                : sanitizeAnyText(content);
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            log.warn("recalled_content のシリアライズ失敗", e);
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length
                > ReflectionConstants.MAX_STRUCTURED_CONTENT_BYTES) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        return serialized;
    }

    /**
     * 永続化済みの JSON 文字列を {@link JsonNode} に復元する（応答生成・マスク判定で使用）。
     */
    public JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("structured_content のパース失敗（fail-closed 判定材料）", e);
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
    }

    // ─── 内部ヘルパ ───────────────────────────────────────────────

    private void sanitizeSection(JsonNode sectionNode) {
        if (!sectionNode.isObject()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        ObjectNode section = (ObjectNode) sectionNode;
        String heading = textOf(section, "heading");
        if (heading != null) {
            section.put("heading", sanitize(heading, ReflectionConstants.MAX_HEADING_LENGTH));
        }
        JsonNode subsNode = section.get("subsections");
        if (subsNode != null && !subsNode.isNull()) {
            if (!subsNode.isArray()) {
                throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
            }
            ArrayNode subs = (ArrayNode) subsNode;
            if (subs.size() > ReflectionConstants.MAX_SUBSECTIONS) {
                throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
            }
            for (JsonNode subNode : subs) {
                sanitizeSubsection(subNode);
            }
        }
    }

    private void sanitizeSubsection(JsonNode subNode) {
        if (!subNode.isObject()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        ObjectNode sub = (ObjectNode) subNode;
        String subHeading = textOf(sub, "sub_heading");
        if (subHeading != null) {
            sub.put("sub_heading", sanitize(subHeading, ReflectionConstants.MAX_HEADING_LENGTH));
        }
        String detail = textOf(sub, "detail");
        if (detail != null) {
            sub.put("detail", sanitize(detail, ReflectionConstants.MAX_DETAIL_LENGTH));
        }
        String supplement = textOf(sub, "supplement");
        if (supplement != null) {
            sub.put("supplement", sanitize(supplement, ReflectionConstants.MAX_DETAIL_LENGTH));
        }
    }

    /** structured_content と同形（main_theme を持つ）の recalled_content をフルサニタイズする。 */
    private JsonNode sanitizeStructuredLike(JsonNode content) {
        ObjectNode root = (ObjectNode) content.deepCopy();
        String mainTheme = textOf(root, "main_theme");
        if (mainTheme != null) {
            root.put("main_theme", sanitize(mainTheme, ReflectionConstants.MAX_HEADING_LENGTH));
        }
        JsonNode sectionsNode = root.get("sections");
        if (sectionsNode != null && sectionsNode.isArray()) {
            for (JsonNode sectionNode : sectionsNode) {
                if (sectionNode.isObject()) {
                    sanitizeSection(sectionNode);
                }
            }
        }
        String freeNote = textOf(root, "free_note");
        if (freeNote != null) {
            root.put("free_note", sanitize(freeNote, ReflectionConstants.MAX_FREE_NOTE_LENGTH));
        }
        return root;
    }

    /** 任意 JSON のテキストノードを再帰的に純テキスト化する（自由テキスト recall 用）。 */
    private JsonNode sanitizeAnyText(JsonNode node) {
        if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    HtmlSanitizer.sanitizePlainText(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(sanitizeAnyText(child));
            }
            return arr;
        }
        if (node.isObject()) {
            ObjectNode obj = objectMapper.createObjectNode();
            node.fields().forEachRemaining(e -> obj.set(e.getKey(), sanitizeAnyText(e.getValue())));
            return obj;
        }
        return node;
    }

    private String textOf(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** 純テキスト化＋字数上限検証（超過は 400）。 */
    private String sanitize(String input, int maxLength) {
        String cleaned = HtmlSanitizer.sanitizePlainText(input);
        if (cleaned != null && cleaned.length() > maxLength) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        return cleaned;
    }
}
