package com.mannschaft.app.tournament.listener;

import com.mannschaft.app.match.MatchCompletedEvent;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchScoringTally;
import com.mannschaft.app.match.service.MatchScoringTallyService;
import com.mannschaft.app.tournament.BasicStatKeys;
import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixturePlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.service.FixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * F08.10 入口①の <b>fixture スナップショット同期器</b>（match → tournament 疎結合・05 §H.2）。
 *
 * <p><b>役割（Phase 5b-1 で明確化・Phase 5b-2 で個人ランキング同期を追加）</b>: 本リスナーは
 * 「<b>matches 正本</b>（{@link MatchCompletedEvent}）を <b>fixture スナップショットへコピーし、
 * 順位（StandingsRecalc）と個人ランキング（RankingsRecalc）を発火する同期器</b>」である。
 * <b>Phase 5b-2</b> で、個人ランキングの基本スタッツ（得点/アシスト）も match ドメインの
 * {@code match_events}（GOAL/ASSIST）を正本とし、本リスナーが {@link MatchScoringTallyService} 経由で
 * 集計して {@code tournament_match_player_stats} スナップショットへ同期する（05 §H.2.2・大会固有 statKey は
 * H.6 で tournament 残置）。{@link RankingsCalculationService} はスナップショット読取のままゆえ
 * ランキングの値・並び順は不変（源泉が match になったことのみが差分）。
 * スコアの正本は matches ドメイン（{@code matches.home_score} 等）であり、
 * {@link TournamentFixtureEntity} のスコア列はそれを高速参照するための<b>派生スナップショット</b>
 * （実体化ビュー・05 §H.2.3）にすぎない。本リスナーがその同期を担う唯一の入口①経路である
 * （正本宣言の詳細は {@link TournamentFixtureEntity} クラス Javadoc を参照）。</p>
 *
 * <p><b>中道（既存 tournament 非破壊）の採用</b>: 05 §H.1 の full Fixture 改称
 * （{@code tournament_matches}→{@code tournament_fixtures} 物理改称・スコア正本移管）は
 * <b>後続フェーズ（Phase 5）に延期</b>する。入口①は本リスナーが {@link MatchCompletedEvent} を
 * {@link TransactionalEventListener}(AFTER_COMMIT) で受信し、<b>既存 {@code tournament_matches} の
 * {@link FixtureService#updateScore} を再利用</b>してスコア反映＋既存の
 * {@code StandingsRecalculationEvent} 発火（既存の非同期順位再計算・冪等）に乗せるだけとする。</p>
 *
 * <p><b>順位再計算経路（第三陣でレース根治済み・05 §H.0.1）</b>: 当初は既存
 * {@code StandingsCalculationService} の {@code @Async @EventListener} 経路を<b>切り替えない</b>方針
 * だったが、{@code @Async @EventListener} は発火元 TX の<b>コミット前</b>に別スレッドで即時実行され、
 * 未コミットのスコアを読んで順位が自動反映されないレース条件が実機 E2E で判明した。第三陣で同サービスの
 * {@code onStandingsRecalculation} を {@code @Async @TransactionalEventListener}(AFTER_COMMIT) /
 * {@code REQUIRES_NEW} へ<b>切替済み</b>（コミット後に確定データを読んで再計算）。詳細は
 * docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.0.1 を参照。</p>
 *
 * <h3>処理（05 §H.2 / 06 §I.2 第一陣）</h3>
 * <ol>
 *   <li>{@code tournamentFixtureId == null}（単独試合＝練習/親善）→ 何もしない。</li>
 *   <li>fixtureId（= {@code tournament_matches.id}・BIGINT）で fixture を引当。無ければ警告ログのみで
 *       終了（例外を投げない＝tournament を越境で壊さない・05 §H.2 (b)）。</li>
 *   <li>fixture の matchday → division から {@code tournamentId} を順引きし、既存
 *       {@link FixtureService#updateScore} を呼ぶ。スコアはイベントのスナップショット（本戦合算済み
 *       {@code homeScore}/{@code awayScore}＋分離 PK）をそのまま渡す。
 *       <b>延長はイベントで本戦に合算済みゆえ extra は使わない（null）</b>。
 *       determineResult / winnerParticipantId は既存ロジックに委ねる。
 *       <b>participant ⇔ side は home participant = HOME 固定</b>（05 §H.1.2）であり、
 *       fixture の {@code home_participant_id} がそのまま本戦 {@code homeScore} を受ける。</li>
 *   <li>{@link FixtureService#updateScore} 内で {@code match.updateScore} により status=COMPLETED が
 *       自動化され、既存の {@code StandingsRecalculationEvent}（division/tournament 指定）が発火する。
 *       順位再計算は既存 {@code @Async}・冪等経路を流用する。</li>
 * </ol>
 *
 * <p><b>冪等</b>: COMPLETED 後の訂正による再発火でも {@code updateScore} は全列上書き（加算ではなく置換）
 * のため冪等（05 §H.2 (d)）。</p>
 *
 * <p><b>トランザクション境界</b>: AFTER_COMMIT 後はアクティブTXが無いため、本リスナーは
 * {@code REQUIRES_NEW} で新規TXを開始してtournament_matchesを更新する。
 * Spring は {@code @TransactionalEventListener} に {@code @Transactional(REQUIRED)} を付けることを
 * 禁じているため（起動時バリデーション失敗・ApplicationContext全滅）、{@code REQUIRES_NEW} が正道。
 * match ドメインの {@code @Transactional} は跨がない（原則 5・05 §H.5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.2 / 06 §I.2</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchScoreFixtureListener {

    private final TournamentFixtureRepository fixtureRepository;
    private final TournamentMatchdayRepository matchdayRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final FixtureService tournamentMatchService;

    // Phase 5b-2: 個人ランキングの基本スタッツ（得点/アシスト）を match_events 正本へ同期する依存（05 §H.2.2）。
    private final MatchScoringTallyService matchScoringTallyService;
    private final TournamentFixturePlayerStatRepository playerStatRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 試合完了イベントを受信し、matches 正本のスコアを fixture スナップショット列へ同期して順位連携する。
     *
     * <p><b>同期処理（05 §H.2.3）</b>: matches 正本（イベントのスコア）を fixture の派生スナップショット列
     * （home/away_score・home/away_penalty_score・status・result・winner_participant_id）へコピーし、
     * 既存 {@code StandingsRecalculationEvent} を発火する。コピー自体は既存
     * {@link FixtureService#updateScore} 内の {@code match.updateScore} に委譲する（機構は不変）。</p>
     *
     * <p>AFTER_COMMIT 発火により、match 側トランザクションがコミット済みのスコアに対してのみ同期する
     * （未コミットのスコアで順位を誤更新しない・05 §H.2 (a)）。冪等（全列上書き・置換）。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMatchCompleted(MatchCompletedEvent event) {
        Long fixtureId = event.getTournamentFixtureId();
        if (fixtureId == null) {
            // 単独試合（練習/親善）は順位連携の対象外（05 §H.2）
            return;
        }

        TournamentFixtureEntity fixture = fixtureRepository.findById(fixtureId).orElse(null);
        if (fixture == null) {
            // 引当不能でも例外を投げない（match 側は既コミット・tournament を越境で壊さない・05 §H.2 (b)）。
            // 症状は握りつぶさず警告ログに残す（フェイルセーフは手動順位再計算 API・05 §H.2 (c)）。
            log.warn("順位連携: fixture 引当不能のためスキップ matchId={}, tournamentFixtureId={}",
                    event.getMatchId(), fixtureId);
            return;
        }

        // matchday → division → tournament を順引きして既存 updateScore の発火経路（divisionId/tournamentId）に合わせる
        Long tournamentId = resolveTournamentId(fixture);
        if (tournamentId == null) {
            log.warn("順位連携: tournamentId 解決不能のためスキップ matchId={}, tournamentFixtureId={}, matchdayId={}",
                    event.getMatchId(), fixtureId, fixture.getMatchdayId());
            return;
        }

        // 既存 updateScore を再利用（determineResult / winner 判定 / status=COMPLETED 自動化 / StandingsRecalc 発火）。
        // 延長はイベントで本戦合算済みゆえ extra は null（05 §H.1 移行表・sports/01_soccer.md §4.1）。
        // PK は分離値をそのまま渡す。participant⇔side は home participant=HOME 固定（05 §H.1.2）。
        // version は null を渡す（F08.7 Wave3a 楽観ロック実効化）。本リスナーは試合記録ドメインからの
        // システム内部同期であり、UI 上の並行編集者ではないため client 版突合の対象外。version=null は
        // updateScore 側で「版チェックなし＝従来挙動」として扱われる。
        ScoreUpdateRequest scoreReq = new ScoreUpdateRequest(
                event.getHomeScore(),
                event.getAwayScore(),
                null,
                null,
                event.getHomePenaltyScore(),
                event.getAwayPenaltyScore(),
                null,
                null,
                null);

        tournamentMatchService.updateScore(tournamentId, fixtureId, scoreReq);
        log.info("順位連携: fixture へスコア反映完了 matchId={}, tournamentFixtureId={}, tournamentId={}, home={}, away={}",
                event.getMatchId(), fixtureId, tournamentId, event.getHomeScore(), event.getAwayScore());

        // Phase 5b-2: 個人ランキングの基本スタッツ（得点/アシスト）を match_events 正本から
        // fixture スナップショット（tournament_match_player_stats）へ同期する（05 §H.2.2）。
        syncBasicPlayerStats(event, fixture, fixtureId, tournamentId);
    }

    /**
     * 当該 fixture の基本スタッツ（得点/アシスト）を match_events 集計から
     * {@code tournament_match_player_stats} スナップショットへ冪等に同期する（F08.10 05 §H.2.2）。
     *
     * <p><b>正本化の機構（案ｱ スナップショット同期）</b>: 個人ランキングの源泉は match ドメインの
     * {@code match_events}（GOAL/ASSIST）に正本化する。tournament は match ドメインの
     * {@link MatchScoringTallyService#tallyScoringStatsForMatch}（プレーン DTO・Entity 越境なし＝ArchUnit 順守）
     * を呼んで選手別の得点/アシストを取得し、それを fixture の派生スナップショットへコピーする。
     * {@link RankingsCalculationService} は従来どおりスナップショットを読むため、<b>ランキングの値・並び順・
     * 同点処理は不変</b>（源泉が match になったことのみが差分）。</p>
     *
     * <p><b>冪等</b>: 当該 fixture（matchId=fixtureId）の基本 statKey 行を delete してから再 insert する
     * （再 COMPLETED の訂正でも置換・二重計上しない・05 §H.2 (d)）。大会固有の独自 statKey（H.6）は
     * {@link BasicStatKeys#ALL} に含めず削除対象外ゆえ tournament 側に残置される。</p>
     *
     * <p><b>participant ⇔ side</b>: home participant=HOME 固定（05 §H.1.2）。HOME 側集計は
     * fixture の {@code homeParticipantId}、AWAY 側集計は {@code awayParticipantId} を participant とする。
     * participant 不明（fixture 未割当）の集計行は順位の participant 表示に使えないため安全側でスキップする
     * （例外を投げず警告ログ・越境で壊さない）。</p>
     *
     * <p><b>RankingsRecalculation 発火</b>: スナップショット更新後に
     * {@link RankingsRecalculationEvent} を発火し、{@link RankingsCalculationService} の
     * AFTER_COMMIT 再計算（冪等）に乗せる。本リスナーは {@code REQUIRES_NEW} TX 内ゆえ、発火イベントは
     * その TX のコミット後に処理される。</p>
     */
    private void syncBasicPlayerStats(MatchCompletedEvent event, TournamentFixtureEntity fixture,
                                      Long fixtureId, Long tournamentId) {
        List<MatchScoringTally> tallies = matchScoringTallyService.tallyScoringStatsForMatch(event.getMatchId());

        // 冪等: 当該 fixture の基本 statKey 行を delete してから再 insert（再発火で二重計上しない）。
        // 大会固有 statKey（H.6）は ALL に含めないため削除されず残置される。
        playerStatRepository.deleteByMatchIdAndStatKeyIn(fixtureId, BasicStatKeys.ALL);

        for (MatchScoringTally tally : tallies) {
            Long participantId = participantIdForSide(fixture, tally.teamSide());
            if (participantId == null) {
                // participant 未割当の集計は順位表示に使えないためスキップ（症状は警告ログに残す）。
                log.warn("順位連携(個人): participant 未割当のためスキップ matchId={}, fixtureId={}, side={}, playerUserId={}",
                        event.getMatchId(), fixtureId, tally.teamSide(), tally.playerUserId());
                continue;
            }
            saveBasicStat(fixtureId, participantId, tally.playerUserId(), BasicStatKeys.GOALS, tally.goals());
            saveBasicStat(fixtureId, participantId, tally.playerUserId(), BasicStatKeys.ASSISTS, tally.assists());
        }

        // 個人ランキング再計算を発火（既存 AFTER_COMMIT 再計算・冪等経路を流用）。
        eventPublisher.publishEvent(new RankingsRecalculationEvent(this, tournamentId));
        log.info("順位連携(個人): 基本スタッツ同期完了 matchId={}, fixtureId={}, tournamentId={}, players={}",
                event.getMatchId(), fixtureId, tournamentId, tallies.size());
    }

    /** イベントの team_side に対応する fixture の participantId を返す（home participant=HOME 固定・05 §H.1.2）。 */
    private Long participantIdForSide(TournamentFixtureEntity fixture, TeamSide side) {
        return side == TeamSide.HOME ? fixture.getHomeParticipantId() : fixture.getAwayParticipantId();
    }

    /** 基本 statKey の選手スタッツ行を新規 insert する（delete 済み前提・常に valueInt で保持）。 */
    private void saveBasicStat(Long fixtureId, Long participantId, Long userId, String statKey, int value) {
        playerStatRepository.save(TournamentFixturePlayerStatEntity.builder()
                .matchId(fixtureId)
                .participantId(participantId)
                .userId(userId)
                .statKey(statKey)
                .valueInt(value)
                .build());
    }

    /**
     * fixture の matchday → division 経由で tournamentId を順引きする（クロスドメイン JOIN ではなく ID 順引き）。
     *
     * @return tournamentId（matchday / division が引けない場合は {@code null}）
     */
    private Long resolveTournamentId(TournamentFixtureEntity fixture) {
        TournamentMatchdayEntity matchday = matchdayRepository.findById(fixture.getMatchdayId()).orElse(null);
        if (matchday == null) {
            return null;
        }
        return divisionRepository.findById(matchday.getDivisionId())
                .map(d -> d.getTournamentId())
                .orElse(null);
    }
}
