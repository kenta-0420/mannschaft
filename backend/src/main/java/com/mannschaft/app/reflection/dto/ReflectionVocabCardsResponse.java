package com.mannschaft.app.reflection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

/**
 * 期間横断 単語帳ビューのレスポンス（F06.5 Phase 4・§13-F-2・EP #23）。
 *
 * <p>指定期間（{@code from}〜{@code to}）内の自分のエントリの TERM_CARD カードを横断抽出した結果。
 * {@code recall_attempts} を一切書き込まない閲覧専用ビュー（AC-59）。{@code ApiResponse<T>} の
 * {@code data} 包みで返す。</p>
 *
 * @param from       期間開始日
 * @param to         期間終了日
 * @param totalCards 抽出された全カード総数（ページング前・AC-61 で 0）
 * @param page       現在ページ（0 始まり）
 * @param size       1 ページのサイズ
 * @param cards      当該ページのカード一覧（0 件なら空配列・AC-61）
 */
@Schema(name = "ReflectionVocabCardsResponse")
@Builder
public record ReflectionVocabCardsResponse(
        LocalDate from,
        LocalDate to,
        int totalCards,
        int page,
        int size,
        List<ReflectionVocabCardItem> cards
) {
}
