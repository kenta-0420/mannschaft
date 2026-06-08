package com.mannschaft.app.match.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * チーム統計のレスポンス DTO（02 §F.3・sports/01_soccer §6.2）。
 *
 * <p><b>枠組み（競技非依存・コア）</b>: 勝敗系（wins/draws/losses・recentForm）・
 * 選手別ランキング（playerRankings・top-N 上限・displayName は退会者匿名化追従・原則 4）・
 * kind 別内訳（byKind）の構造を返す。各指標の算出定義はサッカーカタログ（§6.2）に従う。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F.3</p>
 */
@Getter
@Builder
public class TeamMatchStatsResponse {

    private final Long teamId;
    private final int totalMatches;
    private final int wins;
    private final int draws;
    private final int losses;
    private final int totalGoalsFor;
    private final int totalGoalsAgainst;
    /** 得失点差（totalGoalsFor - totalGoalsAgainst）。 */
    private final int goalDifference;

    /** 直近 N 試合の結果配列（古い→新しい順・W/D/L・null=スコア未確定）。 */
    private final List<String> recentForm;
    /** 選手別ランキング（top-N 上限・bar 用）。 */
    private final List<PlayerRanking> playerRankings;
    /** kind 別内訳。 */
    private final List<KindBreakdown> byKind;

    /** 選手別ランキングのエントリ（bar 用・top-N 上限・displayName 匿名化追従）。 */
    @Getter
    @Builder
    public static class PlayerRanking {
        private final Long userId;
        /** 表示名（退会者は匿名化値・原則 4）。 */
        private final String displayName;
        private final int goals;
        private final int assists;
        private final int minutes;
    }

    /** kind 別内訳（勝敗・得失点）。 */
    @Getter
    @Builder
    public static class KindBreakdown {
        private final String kind;
        private final int matches;
        private final int wins;
        private final int draws;
        private final int losses;
        private final int goalsFor;
        private final int goalsAgainst;
    }
}
