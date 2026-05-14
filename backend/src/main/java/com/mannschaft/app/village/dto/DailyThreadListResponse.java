package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 日次スレッド一覧レスポンス（F17.1 Phase 1 B9 §4.10.2）。
 *
 * @param threads 日次スレッド配列（新しい日付が先頭）
 */
public record DailyThreadListResponse(
        List<DailyThreadResponse> threads
) {

    public static DailyThreadListResponse of(List<DailyThreadResponse> threads) {
        return new DailyThreadListResponse(threads);
    }
}
