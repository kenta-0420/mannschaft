package com.mannschaft.app.match.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 個人キャリア統計のレスポンス DTO（02 §F.1・sports/01_soccer §6.1）。
 *
 * <p><b>枠組み（競技非依存・コア）</b>: 出場系（totalMatches/totalMinutes/starterRate/avgMinutes）・
 * トレンド系（monthlyTrend/seasonTrend/byKind）の構造を返す。{@code goalsPer90} は分母 0 で NULL（02 §未解決 4）。
 * 各指標の具体算出定義はサッカーカタログ（sports/01_soccer §6.1）に従う。</p>
 *
 * <p>チャートが直接描ける形（labels/values に変換しやすい構造）で返し FE 再集計を不要にする（02 §F.5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F.1</p>
 */
@Getter
@Builder
public class UserMatchStatsResponse {

    private final Long userId;
    private final int totalMatches;
    /** Σ computed_minutes（NULL を除外して合算）。 */
    private final int totalMinutes;
    /** GOAL＋PENALTY_GOAL（本戦のみ・PK 戦除外・soccer §6.1）。 */
    private final int goals;
    private final int assists;
    /** 自責点（OWN_GOAL）。 */
    private final int ownGoals;
    private final int yellowCards;
    private final int redCards;
    private final int starterMatches;
    /** starterMatches / totalMatches（分母 0 は 0.0）。 */
    private final double starterRate;
    /** totalMinutes / totalMatches（分母 0 は 0.0）。 */
    private final double avgMinutes;
    /** goals / (totalMinutes/90)。totalMinutes=0 のとき NULL（0 除算を握りつぶさない・02 §未解決 4）。 */
    private final Double goalsPer90;

    /** 月別推移（line 用）。 */
    private final List<MonthlyStat> monthlyTrend;
    /** シーズン別推移（line 用・MVP は暦年・02 §未解決 5）。 */
    private final List<SeasonStat> seasonTrend;
    /** kind 別内訳（doughnut/bar 用）。 */
    private final List<KindStat> byKind;

    /** 月別の集計エントリ（line 用）。 */
    @Getter
    @Builder
    public static class MonthlyStat {
        /** ISO 月（例 "2026-04"）。 */
        private final String month;
        private final int matches;
        private final int minutes;
        private final int goals;
        private final int assists;
    }

    /** シーズン別の集計エントリ（line 用）。 */
    @Getter
    @Builder
    public static class SeasonStat {
        /** シーズンラベル（MVP は暦年・例 "2026"）。 */
        private final String season;
        private final int matches;
        private final int minutes;
        private final int goals;
        private final int assists;
    }

    /** kind 別の集計エントリ（doughnut/bar 用）。 */
    @Getter
    @Builder
    public static class KindStat {
        /** MatchKind の名称（PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE）。 */
        private final String kind;
        private final int matches;
        private final int minutes;
        private final int goals;
        private final int assists;
    }
}
