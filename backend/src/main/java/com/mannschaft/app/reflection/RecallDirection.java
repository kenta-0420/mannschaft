package com.mannschaft.app.reflection;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 暗記カード（TERM_CARD）の出題方向（F06.5 Phase 4・§13-B）。
 *
 * <p>到来済み想起予定日（{@code ≤ today} に絞った {@code arrivedDueDates}）の個数 {@code k} の
 * パリティ（{@code n = k - 1} の偶奇）で決定論的に算出する。{@code recall_attempts} 件数には依存しない。</p>
 *
 * <ul>
 *   <li>{@code n} 偶数（初回 {@code n=0}）→ {@link #MEANING_TO_TERM}: 意味を表示し語句を入力</li>
 *   <li>{@code n} 奇数 → {@link #TERM_TO_MEANING}: 語句を表示し意味を入力</li>
 * </ul>
 */
@Schema(name = "RecallDirection", description = "暗記カード（TERM_CARD）の出題方向（§13-B）。到来済み想起予定日数のパリティで決定論的に算出する。")
public enum RecallDirection {
    /** 意味を表示し語句を入力（cue=MEANING・answer=TERM）。 */
    MEANING_TO_TERM,
    /** 語句を表示し意味を入力（cue=TERM・answer=MEANING）。 */
    TERM_TO_MEANING
}
