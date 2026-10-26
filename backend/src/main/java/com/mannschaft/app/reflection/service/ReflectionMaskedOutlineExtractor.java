package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionOutlineRevealLevel;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OUTLINE 段階式マスク（足場ラダー）の足場テキストを抽出する（F06.5・§13-C 増分・セキュリティ核心）。
 *
 * <p><b>fail-closed の核心</b>: マスク中の応答には、許可テキスト（{@code main_theme}・各 OUTLINE
 * section の {@code heading}）のみを開示レベルに応じて詰める。<b>小見出し（sub_heading）・詳細（detail）・
 * 補足（supplement）はどの応答にも一切載せない</b>（null を入れるのでなくフィールドごと不搭載）。
 * TERM_CARD section は触らない（cue は {@link ReflectionMaskedCueExtractor} が担当）。</p>
 *
 * <ul>
 *   <li>{@link ReflectionOutlineRevealLevel#HIDDEN}（または null/parse 不能）→ 空（mainTheme=null・sections 空）</li>
 *   <li>{@link ReflectionOutlineRevealLevel#FULL} → main_theme・各 OUTLINE heading を全文</li>
 *   <li>{@link ReflectionOutlineRevealLevel#PARTIAL} → main_theme・heading を先頭数コードポイントに切る</li>
 * </ul>
 */
@Slf4j
@Component
public class ReflectionMaskedOutlineExtractor {

    /**
     * 足場テキストを抽出する（§13-C 増分）。
     *
     * @param content パース済みの本文 JSON（null 可）
     * @param level   開示レベル（null は HIDDEN 扱い＝fail-closed）
     * @return 足場（許可テキストのみ・答え側フィールドは持たない）
     */
    private static final String SECTION_TYPE_TERM_CARD = "TERM_CARD";

    public ReflectionEntryResponse.MaskedOutlineScaffold extractScaffold(
            JsonNode content, ReflectionOutlineRevealLevel level) {
        // fail-closed: HIDDEN／null level／本文欠落・非オブジェクトは足場ゼロ。
        if (level == null || level == ReflectionOutlineRevealLevel.HIDDEN
                || content == null || !content.isObject()) {
            return hidden();
        }
        try {
            // sections の型不整合（配列でない）は足場を一切出さない（fail-closed・AC-86）。
            JsonNode sectionsNode = content.get("sections");
            List<ReflectionEntryResponse.MaskedOutlineSection> sections = new ArrayList<>();
            if (sectionsNode != null && !sectionsNode.isNull()) {
                if (!sectionsNode.isArray()) {
                    return hidden();
                }
                for (JsonNode section : sectionsNode) {
                    if (section == null || !section.isObject()) {
                        continue;
                    }
                    // type 欠落は OUTLINE 扱い（§13-A-1）。TERM_CARD は足場に含めない（cue 抽出が担当）。
                    String type = textOrNull(section, "type");
                    if (SECTION_TYPE_TERM_CARD.equals(type)) {
                        continue;
                    }
                    // 許可テキストは heading のみ。小見出し/詳細/補足はフィールドごと不搭載（fail-closed）。
                    String heading = applyLevel(textOrNull(section, "heading"), level);
                    sections.add(ReflectionEntryResponse.MaskedOutlineSection.builder()
                            .heading(heading)
                            .build());
                }
            }
            String mainTheme = applyLevel(textOrNull(content, "main_theme"), level);
            return ReflectionEntryResponse.MaskedOutlineScaffold.builder()
                    .level(level)
                    .mainTheme(mainTheme)
                    .sections(sections)
                    .build();
        } catch (Exception e) {
            // 型不整合・想定外例外は答え・足場を絶対に載せない（fail-closed・AC-86）。
            log.warn("OUTLINE 足場抽出に失敗のため fail-closed（HIDDEN）: error={}", e.getMessage());
            return hidden();
        }
    }

    /**
     * 開示レベルに応じて許可テキストを整形する。
     * FULL=全文 / PARTIAL=先頭 {@code OUTLINE_PARTIAL_HINT_PREFIX_LENGTH} コードポイント。
     */
    private String applyLevel(String text, ReflectionOutlineRevealLevel level) {
        if (text == null || level == ReflectionOutlineRevealLevel.FULL) {
            return text;
        }
        // PARTIAL のみここに到達（HIDDEN は呼び出し側で短絡済み）。
        return truncateByCodePoints(text, ReflectionConstants.OUTLINE_PARTIAL_HINT_PREFIX_LENGTH);
    }

    /**
     * コードポイント単位で先頭 {@code maxCodePoints} 個に切り詰める（日本語マルチバイト・サロゲートペア安全）。
     * 文字数が上限以下ならそのまま返す。
     */
    private String truncateByCodePoints(String text, int maxCodePoints) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length <= maxCodePoints) {
            return text;
        }
        return new String(codePoints, 0, maxCodePoints);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** 足場ゼロ（HIDDEN・mainTheme=null・sections 空）を返す（fail-closed の既定）。 */
    private ReflectionEntryResponse.MaskedOutlineScaffold hidden() {
        return ReflectionEntryResponse.MaskedOutlineScaffold.builder()
                .level(ReflectionOutlineRevealLevel.HIDDEN)
                .mainTheme(null)
                .sections(List.of())
                .build();
    }
}
