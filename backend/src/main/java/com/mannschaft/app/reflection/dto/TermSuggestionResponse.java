package com.mannschaft.app.reflection.dto;

import lombok.Builder;

/**
 * 学年・学期自動提案レスポンス（F06.5 Phase 3・EP #22・§12.1）。
 *
 * <p>個人時間割から基準日に有効な学年・学期を提案する。
 * 対応する時間割が存在しない場合は両フィールドが null になる。</p>
 *
 * @param academicYear 提案する学年度（Integer型・null=提案なし）
 * @param termLabel    提案する学期ラベル（null=提案なし）
 */
@Builder
public record TermSuggestionResponse(
        Integer academicYear,
        String termLabel
) {
}
