package com.mannschaft.app.match;

import com.mannschaft.app.match.domain.MatchStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * 試合の COMPLETED 遷移を tournament ドメインへ伝えるドメインイベント（05 §H.2）。
 *
 * <p><b>match ドメインが発火するのみ</b>（受信側 = tournament の {@code StandingsCalculationService} は
 * Phase 5 で {@code @TransactionalEventListener(phase=AFTER_COMMIT)} に切替える）。
 * match → tournament は本イベントで疎結合に越境し、{@code @Transactional} はドメインを跨がない（原則 5・05 §H.5）。</p>
 *
 * <p>受信側はこのスナップショット値で fixture へ実体化ビューをコピーし、自ドメイン内で順位計算を完結させる
 * （クロスドメイン JOIN 回避・05 §H.2.3）。COMPLETED 後の訂正による再発火でも常に最新値で<b>置換</b>するため
 * 冪等（加算ではない・05 §H.2 (d)）。</p>
 *
 * <p>スコアは本戦（{@code homeScore}/{@code awayScore}・延長合算）と PK 戦（{@code homePenaltyScore}/
 * {@code awayPenaltyScore}）を分離して保持する（sports/01_soccer.md §4.1）。
 * {@code tournamentFixtureId} が {@code null} の単独試合（練習/親善）でも発火しうるが、受信側は fixture リンクの
 * ある試合のみ順位反映する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.2</p>
 */
@Getter
@Builder
@ToString
public class MatchCompletedEvent {

    /** 完了した試合 ID（UUIDv7）。 */
    private final UUID matchId;

    /** リンクする大会 fixture ID（BIGINT・NULL=単独試合）。 */
    private final Long tournamentFixtureId;

    /** ホーム本戦スコア（延長得点合算済み・NULL 可）。 */
    private final Integer homeScore;

    /** アウェイ本戦スコア（延長得点合算済み・NULL 可）。 */
    private final Integer awayScore;

    /** ホーム PK 戦スコア（本戦と分離・NULL=PK 戦なし）。 */
    private final Integer homePenaltyScore;

    /** アウェイ PK 戦スコア（本戦と分離・NULL=PK 戦なし）。 */
    private final Integer awayPenaltyScore;

    /** 遷移後の status（通常 COMPLETED）。 */
    private final MatchStatus status;
}
