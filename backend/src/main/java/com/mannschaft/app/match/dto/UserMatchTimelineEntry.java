package com.mannschaft.app.match.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 個人タイムラインの 1 試合エントリ（02 §F.2・ページング対象）。
 *
 * <p>出場した試合を時系列で返す。個人分析画面の試合履歴リスト・ライン推移の元データ。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F.2</p>
 */
@Getter
@Builder
public class UserMatchTimelineEntry {

    private final UUID matchId;
    private final LocalDateTime kickoffAt;
    /** 相手表示名（登録相手名 or 手入力相手名）。 */
    private final String opponent;
    /** 当該試合の出場分（NULL=不明）。 */
    private final Integer computedMinutes;
    private final int goals;
    private final int assists;
    private final int yellowCards;
    private final int redCards;
    /** 勝敗（"W"/"D"/"L"・本人サイドと本戦スコアから判定・soccer §4.3）。スコア未確定は null。 */
    private final String result;
}
