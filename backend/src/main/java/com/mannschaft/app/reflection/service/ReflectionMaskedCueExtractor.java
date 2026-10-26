package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * マスク中の TERM_CARD section から cue 側だけを抽出する（F06.5 Phase 4・§13-C-1）。
 *
 * <p><b>セキュリティ核心・fail-closed</b>: 出題方向（{@link RecallDirection}）に応じて cue 側のみを
 * {@link ReflectionEntryResponse.MaskedCardPrompt#promptText} に詰める。答え側はフィールドごと載せない。
 * cue/answer の取り違えによる静的漏洩（§13-C-2 M-4）を防ぐため、direction と term/meaning の対応を厳密に
 * 取り扱う。旧形（{@code type} 欠落=OUTLINE）・{@code cards} 欠落・空フィールドでも答えが漏れないこと。</p>
 *
 * <ul>
 *   <li>OUTLINE section（または type 欠落＝OUTLINE）は cue も出さない（完全非表示・§13-C）</li>
 *   <li>{@code direction == MEANING_TO_TERM} → cue は meaning（promptSide="MEANING"）。term は載せない</li>
 *   <li>{@code direction == TERM_TO_MEANING} → cue は term（promptSide="TERM"）。meaning は載せない</li>
 *   <li>{@code direction == null}（today=null など算出不能）→ 空リストを返す（cue を一切出さない）</li>
 * </ul>
 */
@Slf4j
@Component
public class ReflectionMaskedCueExtractor {

    private static final String SECTION_TYPE_TERM_CARD = "TERM_CARD";
    private static final String PROMPT_SIDE_MEANING = "MEANING";
    private static final String PROMPT_SIDE_TERM = "TERM";

    /**
     * structured_content から TERM_CARD section の cue クイズを抽出する（§13-C-1）。
     *
     * @param structuredContent パース済みの本文 JSON（null 可）
     * @param direction         §13-B で算出した出題方向（null なら cue を一切出さない＝fail-closed）
     * @return cue 側のみを載せた {@code cardQuiz}（TERM_CARD が無ければ空リスト）
     */
    public List<ReflectionEntryResponse.MaskedCardQuiz> extractCardQuiz(
            JsonNode structuredContent, RecallDirection direction) {
        List<ReflectionEntryResponse.MaskedCardQuiz> result = new ArrayList<>();
        // fail-closed: 方向が決まらない・本文が無い・section が無いときは cue を出さない。
        if (direction == null || structuredContent == null || !structuredContent.isObject()) {
            return result;
        }
        JsonNode sectionsNode = structuredContent.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray()) {
            return result;
        }
        for (JsonNode section : sectionsNode) {
            if (section == null || !section.isObject()) {
                continue;
            }
            // type 欠落は OUTLINE 扱い（§13-A-1）。OUTLINE は cue を出さない。
            String type = textOrNull(section, "type");
            if (!SECTION_TYPE_TERM_CARD.equals(type)) {
                continue;
            }
            JsonNode cardsNode = section.get("cards");
            if (cardsNode == null || !cardsNode.isArray() || cardsNode.isEmpty()) {
                continue;
            }
            List<ReflectionEntryResponse.MaskedCardPrompt> prompts = new ArrayList<>();
            for (JsonNode card : cardsNode) {
                if (card == null || !card.isObject()) {
                    continue;
                }
                ReflectionEntryResponse.MaskedCardPrompt prompt = toCuePrompt(card, direction);
                if (prompt != null) {
                    prompts.add(prompt);
                }
            }
            result.add(ReflectionEntryResponse.MaskedCardQuiz.builder()
                    .heading(textOrNull(section, "heading"))
                    .direction(direction)
                    .prompts(prompts)
                    .build());
        }
        return result;
    }

    /**
     * 1 枚のカードから cue 側のみのプロンプトを作る。答え側は絶対に載せない（fail-closed）。
     *
     * @return cue が存在すればプロンプト、cue 側が欠落/空なら null（その場合も答えは載せない）
     */
    private ReflectionEntryResponse.MaskedCardPrompt toCuePrompt(JsonNode card, RecallDirection direction) {
        if (direction == RecallDirection.MEANING_TO_TERM) {
            // cue = meaning。term（答え）は読み取りも詰めもしない。
            String cue = textOrNull(card, "meaning");
            if (cue == null || cue.isBlank()) {
                return null;
            }
            return ReflectionEntryResponse.MaskedCardPrompt.builder()
                    .promptSide(PROMPT_SIDE_MEANING)
                    .promptText(cue)
                    .build();
        }
        if (direction == RecallDirection.TERM_TO_MEANING) {
            // cue = term。meaning（答え）は読み取りも詰めもしない。
            String cue = textOrNull(card, "term");
            if (cue == null || cue.isBlank()) {
                return null;
            }
            return ReflectionEntryResponse.MaskedCardPrompt.builder()
                    .promptSide(PROMPT_SIDE_TERM)
                    .promptText(cue)
                    .build();
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
