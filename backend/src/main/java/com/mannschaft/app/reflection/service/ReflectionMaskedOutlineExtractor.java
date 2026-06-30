package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.ReflectionOutlineRevealLevel;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    public ReflectionEntryResponse.MaskedOutlineScaffold extractScaffold(
            JsonNode content, ReflectionOutlineRevealLevel level) {
        // TODO(green): 足場抽出の実装。red フェーズでは fail-closed の空 HIDDEN を返す。
        return hidden();
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
