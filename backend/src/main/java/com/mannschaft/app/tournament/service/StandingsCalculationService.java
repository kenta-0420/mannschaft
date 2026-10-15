package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.tournament.FixtureResult;
import com.mannschaft.app.tournament.FixtureStatus;
import com.mannschaft.app.tournament.PromotionZone;
import com.mannschaft.app.tournament.StandingsRecalculationEvent;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureSetEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureSetRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import com.mannschaft.app.tournament.repository.TournamentTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 順位表の自動計算サービス。試合結果入力時に非同期で再計算する。
 * 冪等方式: 毎回全COMPLETED試合からゼロ計算してUPSERTする。
 *
 * <p><b>スコア源泉は fixture スナップショット列（05 §H.2.1 / H.2.3）</b>: 本サービスは
 * {@link TournamentFixtureEntity} のスコア列（{@code homeScore} / {@code awayScore} /
 * {@code result} / {@code status} 等）を読んで勝点・順位を計算する。これらの列は
 * <b>matches ドメインを正本とする派生スナップショット</b>であり（実体化ビュー・05 §H.2.3）、
 * クロスドメイン JOIN（CLAUDE.md 原則 1 違反・{@code CrossDomainEntityImportArchTest} が禁ずる）を
 * 避けるため fixture 自ドメイン内で順位計算が完結するよう設計されている。matches へは直接 JOIN しない。
 * スナップショットの書込（同期）は入口①の
 * {@link com.mannschaft.app.tournament.listener.MatchScoreFixtureListener} および
 * {@link FixtureService#updateScore}/{@code batchUpdateScores} が担う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandingsCalculationService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentFixtureRepository matchRepository;
    private final TournamentFixtureSetRepository matchSetRepository;
    private final TournamentStandingRepository standingRepository;
    private final TournamentTiebreakerRepository tiebreakerRepository;

    /**
     * 順位表再計算イベントを受信する。
     *
     * <p><b>レース条件根治（05 §H.0 訂正）</b>: 以前は {@code @Async @EventListener} だったため、
     * 発火元TX（{@link FixtureService#updateScore} の {@code @Transactional}、および入口①
     * {@code MatchScoreFixtureListener} の {@code REQUIRES_NEW}）の<b>コミット前</b>に別スレッドで
     * 即時実行され、未コミットのスコア（{@code played=0} 等）を読んで順位表が自動反映されなかった。
     * これを {@link TransactionalEventListener}(AFTER_COMMIT) に切り替え、発火元TXの
     * <b>コミット後</b>に確定データを読んで再計算する（手動再計算なしで自動反映が確定する）。</p>
     *
     * <p><b>{@code @Async} は併存可</b>: {@code @TransactionalEventListener}(AFTER_COMMIT) が
     * コミット後にリスナー呼び出しを発生させ、その呼び出しを {@code @Async} が別スレッドへ逃がす。
     * したがって「コミット後」かつ「非同期（呼び出し元をブロックしない）」が両立する。</p>
     *
     * <p><b>TX境界</b>: AFTER_COMMIT 後はアクティブTXが無いため {@code REQUIRES_NEW} で新規TXを開始する。
     * Spring は {@code @TransactionalEventListener} に {@code @Transactional(REQUIRED)} を付けることを
     * 禁じている（起動時バリデーション失敗・ApplicationContext全滅）ため {@code REQUIRES_NEW} が正道。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。勝敗表の再計算であり、再開後の再計算要求で現在値へ収束する。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStandingsRecalculation(StandingsRecalculationEvent event) {
        recalculate(event.getDivisionId(), event.getTournamentId());
    }

    /**
     * 手動再計算（トラブルリカバリ用）。
     */
    @Transactional
    public void recalculate(Long divisionId, Long tournamentId) {
        log.info("順位表再計算開始: divisionId={}, tournamentId={}", divisionId, tournamentId);

        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null) return;

        TournamentDivisionEntity division = divisionRepository.findById(divisionId).orElse(null);
        if (division == null) return;

        List<TournamentParticipantEntity> participants =
                participantRepository.findByDivisionIdOrderBySeedAsc(divisionId);
        // COMPLETED 抽出・以降のスコア集計は fixture スナップショット列（matches 正本の派生・05 §H.2.3）由来。
        // matches へクロスドメイン JOIN せず fixture 自ドメイン内で順位計算が完結する（CLAUDE.md 原則 1）。
        List<TournamentFixtureEntity> completedMatches =
                matchRepository.findByDivisionIdAndStatus(divisionId, FixtureStatus.COMPLETED);
        List<TournamentTiebreakerEntity> tiebreakers =
                tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(tournamentId);

        // 各チームの成績を集計
        Map<Long, TeamStats> statsMap = new HashMap<>();
        for (TournamentParticipantEntity p : participants) {
            statsMap.put(p.getId(), new TeamStats(p.getId()));
        }

        for (TournamentFixtureEntity match : completedMatches) {
            processMatch(match, statsMap, tournament);
        }

        // タイブレークルールに従ってソート
        List<TeamStats> sortedStats = new ArrayList<>(statsMap.values());
        sortedStats.sort(buildComparator(tiebreakers, completedMatches, statsMap));

        // 順位の割り当てとプロモーションゾーン判定
        int totalParticipants = sortedStats.size();
        for (int i = 0; i < sortedStats.size(); i++) {
            TeamStats stats = sortedStats.get(i);
            int rank = i + 1;

            PromotionZone zone = determinePromotionZone(rank, totalParticipants, division);

            // 直近5試合のform計算
            String form = calculateForm(stats.participantId, completedMatches);

            TournamentStandingEntity standing = standingRepository
                    .findByDivisionIdAndParticipantId(divisionId, stats.participantId)
                    .orElse(TournamentStandingEntity.builder()
                            .divisionId(divisionId)
                            .participantId(stats.participantId)
                            .rank(rank)
                            .build());

            standing.updateStats(rank, stats.played, stats.wins, stats.draws, stats.losses,
                    stats.scoreFor, stats.scoreAgainst, stats.scoreFor - stats.scoreAgainst,
                    stats.points, stats.bonusPoints, stats.setsWon, stats.setsLost,
                    form, zone);
            standingRepository.save(standing);
        }

        log.info("順位表再計算完了: divisionId={}", divisionId);
    }

    private void processMatch(TournamentFixtureEntity match, Map<Long, TeamStats> statsMap,
                               TournamentEntity tournament) {
        Long homeId = match.getHomeParticipantId();
        Long awayId = match.getAwayParticipantId();
        if (homeId == null || awayId == null) return;

        TeamStats homeStats = statsMap.get(homeId);
        TeamStats awayStats = statsMap.get(awayId);
        if (homeStats == null || awayStats == null) return;

        homeStats.played++;
        awayStats.played++;

        // 得失点・勝敗は fixture スナップショット列（matches 正本の派生・05 §H.2.3）を読む。
        int homeScore = match.getHomeScore() != null ? match.getHomeScore() : 0;
        int awayScore = match.getAwayScore() != null ? match.getAwayScore() : 0;
        homeStats.scoreFor += homeScore;
        homeStats.scoreAgainst += awayScore;
        awayStats.scoreFor += awayScore;
        awayStats.scoreAgainst += homeScore;

        // セット別集計
        List<TournamentFixtureSetEntity> sets = matchSetRepository.findByMatchIdOrderBySetNumberAsc(match.getId());
        int homeSetsWon = 0, awaySetsWon = 0;
        for (TournamentFixtureSetEntity set : sets) {
            if (set.getHomeScore() > set.getAwayScore()) homeSetsWon++;
            else if (set.getAwayScore() > set.getHomeScore()) awaySetsWon++;
        }
        homeStats.setsWon += homeSetsWon;
        homeStats.setsLost += awaySetsWon;
        awayStats.setsWon += awaySetsWon;
        awayStats.setsLost += homeSetsWon;

        // 勝敗と勝点
        FixtureResult result = match.getResult();
        boolean isHomeWin = result == FixtureResult.HOME_WIN || result == FixtureResult.FORFEIT_HOME_WIN;
        boolean isAwayWin = result == FixtureResult.AWAY_WIN || result == FixtureResult.FORFEIT_AWAY_WIN;
        boolean isDraw = result == FixtureResult.DRAW;

        if (isHomeWin) {
            homeStats.wins++;
            awayStats.losses++;
            homeStats.points += tournament.getWinPoints();
            awayStats.points += tournament.getLossPoints();
        } else if (isAwayWin) {
            awayStats.wins++;
            homeStats.losses++;
            awayStats.points += tournament.getWinPoints();
            homeStats.points += tournament.getLossPoints();
        } else if (isDraw) {
            homeStats.draws++;
            awayStats.draws++;
            homeStats.points += tournament.getDrawPoints();
            awayStats.points += tournament.getDrawPoints();
        }

        // bonus_point_rules の処理はここで評価可能（将来拡張用フック）
        // 現時点ではJSONパースとルール評価のスケルトンを残す
    }

    private Comparator<TeamStats> buildComparator(List<TournamentTiebreakerEntity> tiebreakers,
                                                   List<TournamentFixtureEntity> matches,
                                                   Map<Long, TeamStats> statsMap) {
        Comparator<TeamStats> comparator = (a, b) -> 0;

        for (TournamentTiebreakerEntity tb : tiebreakers) {
            int dir = tb.getDirection() == com.mannschaft.app.tournament.TiebreakerDirection.DESC ? -1 : 1;
            Comparator<TeamStats> c = switch (tb.getCriteria()) {
                case POINTS -> Comparator.comparingInt((TeamStats s) -> s.points);
                case SCORE_DIFFERENCE -> Comparator.comparingInt((TeamStats s) -> s.scoreFor - s.scoreAgainst);
                case SCORE_FOR -> Comparator.comparingInt((TeamStats s) -> s.scoreFor);
                case WINS -> Comparator.comparingInt((TeamStats s) -> s.wins);
                case LOSSES -> Comparator.comparingInt((TeamStats s) -> s.losses);
                case DRAWS -> Comparator.comparingInt((TeamStats s) -> s.draws);
                case SET_RATIO -> Comparator.comparingDouble((TeamStats s) ->
                        s.setsLost == 0 ? Double.MAX_VALUE : (double) s.setsWon / s.setsLost);
                case POINT_RATIO -> Comparator.comparingDouble((TeamStats s) ->
                        s.scoreAgainst == 0 ? Double.MAX_VALUE : (double) s.scoreFor / s.scoreAgainst);
                case HEAD_TO_HEAD_POINTS, HEAD_TO_HEAD_SCORE_DIFFERENCE ->
                        Comparator.comparingInt((TeamStats s) -> 0); // simplified
            };
            comparator = comparator.thenComparing(dir == -1 ? c.reversed() : c);
        }

        // デフォルト: 勝点降順
        if (tiebreakers.isEmpty()) {
            comparator = Comparator.comparingInt((TeamStats s) -> -s.points);
        }

        return comparator;
    }

    private PromotionZone determinePromotionZone(int rank, int total,
                                                  TournamentDivisionEntity division) {
        if (division.getPromotionSlots() > 0 && rank <= division.getPromotionSlots()) {
            return PromotionZone.PROMOTED;
        }
        if (division.getPlayoffPromotionSlots() > 0 &&
            rank <= division.getPromotionSlots() + division.getPlayoffPromotionSlots()) {
            return PromotionZone.PLAYOFF;
        }
        if (division.getRelegationSlots() > 0 && rank > total - division.getRelegationSlots()) {
            return PromotionZone.RELEGATED;
        }
        return PromotionZone.SAFE;
    }

    private String calculateForm(Long participantId, List<TournamentFixtureEntity> matches) {
        List<TournamentFixtureEntity> teamMatches = matches.stream()
                .filter(m -> participantId.equals(m.getHomeParticipantId()) ||
                             participantId.equals(m.getAwayParticipantId()))
                .sorted(Comparator.comparing(TournamentFixtureEntity::getCreatedAt).reversed())
                .limit(5)
                .toList();

        StringBuilder form = new StringBuilder();
        for (TournamentFixtureEntity m : teamMatches) {
            boolean isHome = participantId.equals(m.getHomeParticipantId());
            FixtureResult r = m.getResult();
            if (r == FixtureResult.DRAW) {
                form.append('D');
            } else if ((isHome && (r == FixtureResult.HOME_WIN || r == FixtureResult.FORFEIT_HOME_WIN)) ||
                       (!isHome && (r == FixtureResult.AWAY_WIN || r == FixtureResult.FORFEIT_AWAY_WIN))) {
                form.append('W');
            } else {
                form.append('L');
            }
        }
        return form.length() > 0 ? form.toString() : null;
    }

    /**
     * チーム成績の内部集計用データクラス。
     */
    private static class TeamStats {
        final Long participantId;
        int played = 0;
        int wins = 0;
        int draws = 0;
        int losses = 0;
        int scoreFor = 0;
        int scoreAgainst = 0;
        int points = 0;
        int bonusPoints = 0;
        int setsWon = 0;
        int setsLost = 0;

        TeamStats(Long participantId) {
            this.participantId = participantId;
        }
    }
}
