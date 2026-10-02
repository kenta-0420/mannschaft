package com.mannschaft.app.recruitment.dto;

import java.util.List;

/**
 * F03.11.1 キャンセル料記録一覧の 1 スライス（キーセットページングの結果）。
 *
 * <p>総件数（{@code totalElements}）は持たない。一覧は DB の絞り込みの後に
 * <b>権威ある受取先による絞り込み</b>をアプリ層で通すため、DB だけで総件数を数えることができない
 * （数えれば必ず過大な嘘になる）。数えられない値を返すより、続きの有無
 * （{@code hasNext}）と続きの位置（{@code nextCursor}）だけを正直に返す。</p>
 *
 * @param records    このスライスの記録
 * @param nextCursor 続きを取るためのカーソル（続きが無ければ {@code null}）
 * @param hasNext    続きがあるか
 */
public record RecruitmentCancellationRecordSlice(
        List<RecruitmentCancellationRecordSummaryResponse> records,
        String nextCursor,
        boolean hasNext) {
}
