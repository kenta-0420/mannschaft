package com.mannschaft.app.reflection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;

/**
 * 期間横断 単語帳ビューの 1 カード（F06.5 Phase 4・§13-F-2・EP #23）。
 *
 * <p>TERM_CARD section の {@code cards[]} をアプリ層で抽出し、出典メタ（テーマ・対象日・section 見出し）を
 * 付与したもの。本人の PRIVATE データのみを返す（他人のカードは含めない・AC-60）。</p>
 *
 * @param term           語句／表
 * @param meaning        意味／裏
 * @param themeId        出典テーマID
 * @param themeTitle     出典テーマ名
 * @param targetDate     出典エントリの対象日
 * @param sectionHeading 出典 section の見出し
 */
@Schema(name = "ReflectionVocabCardItem")
@Builder
public record ReflectionVocabCardItem(
        String term,
        String meaning,
        String themeId,
        String themeTitle,
        LocalDate targetDate,
        String sectionHeading
) {
}
