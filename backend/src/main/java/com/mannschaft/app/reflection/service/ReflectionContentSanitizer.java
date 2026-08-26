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

        // Phase 4（§13-A-3）: section.type を許可リスト検証（欠落=OUTLINE 正規化・不正値は 400）。
        sanitizeSectionType(section);

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

        // Phase 4（§13-A-3）: TERM_CARD の cards[] 枚数・term/meaning を検証＋全 HTML 平文化。
        sanitizeCards(section);
    }

    /**
     * section.type を検証し OUTLINE/TERM_CARD のいずれかへ正規化する（§13-A-1 / §13-A-3）。
     *
     * <p>欠落・null・空文字は {@code OUTLINE} に正規化して書き戻す（後方互換・AC-50）。
     * {@code OUTLINE}/{@code TERM_CARD} 以外の値は 400（REFLECTION_007・AC-54）。</p>
     */
    private void sanitizeSectionType(ObjectNode section) {
        JsonNode typeNode = section.get("type");
        if (typeNode == null || typeNode.isNull()) {
            section.put("type", "OUTLINE");
            return;
        }
        if (!typeNode.isTextual()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        String type = typeNode.asText();
        if (type.isBlank()) {
            section.put("type", "OUTLINE");
            return;
        }
        if (!"OUTLINE".equals(type) && !"TERM_CARD".equals(type)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        section.put("type", type);
    }

    /**
     * TERM_CARD の cards[] を検証・サニタイズする（§13-A-3 / AC-49 / AC-54）。
     *
     * <p>枚数 ≤ {@code MAX_CARDS_PER_SECTION}（50）・term/meaning 各 ≤ 200 字。各 term/meaning は
     * {@link #sanitize}（=全 HTML 平文化）に通す（detail/supplement/heading と完全に同経路）。
     * cards 欠落・空配列・非配列の扱い: 欠落/null は何もしない（後方互換）。配列でない場合は 400。</p>
     */
    private void sanitizeCards(ObjectNode section) {
        JsonNode cardsNode = section.get("cards");
        if (cardsNode == null || cardsNode.isNull()) {
            return; // cards 欠落は OUTLINE/旧形と整合（後方互換・AC-50）。
        }
        if (!cardsNode.isArray()) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        ArrayNode cards = (ArrayNode) cardsNode;
        if (cards.size() > ReflectionConstants.MAX_CARDS_PER_SECTION) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        for (JsonNode cardNode : cards) {
            if (!cardNode.isObject()) {
                throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
            }
            ObjectNode card = (ObjectNode) cardNode;
            String term = textOf(card, "term");
            if (term != null) {
                card.put("term", sanitize(term, ReflectionConstants.MAX_CARD_TERM_LENGTH));
            }
            String meaning = textOf(card, "meaning");
            if (meaning != null) {
                card.put("meaning", sanitize(meaning, ReflectionConstants.MAX_CARD_MEANING_LENGTH));
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
