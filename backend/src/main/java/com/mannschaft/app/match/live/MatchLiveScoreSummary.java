package com.mannschaft.app.match.live;

import lombok.Builder;
import lombok.Getter;

/**
 * F08.10 / 07 §J.2.1 ライブ配信のスコアサマリ（公開可能な進行情報のみ）。
 *
 * <p>本戦スコア（延長合算済み）と PK 戦スコアを分離して保持する（sports/01_soccer.md §4.1）。
 * 機微情報（所有チーム ID・編集権限・内部 ID）は一切含めない（07 §J.3.3 二重防御）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2.1 / §J.3.3</p>
 */
@Getter
@Builder
public class MatchLiveScoreSummary {

    /** ホーム本戦スコア（延長合算済み・NULL 可）。 */
    private final Integer homeScore;

    /** アウェイ本戦スコア（延長合算済み・NULL 可）。 */
    private final Integer awayScore;

    /** ホーム PK 戦スコア（本戦と分離・NULL=PK 戦なし）。 */
    private final Integer homePenaltyScore;

    /** アウェイ PK 戦スコア（本戦と分離・NULL=PK 戦なし）。 */
    private final Integer awayPenaltyScore;
}
