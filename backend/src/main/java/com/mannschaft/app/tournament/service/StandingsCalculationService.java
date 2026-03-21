package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.MatchResult;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.PromotionZone;
import com.mannschaft.app.tournament.StandingsRecalculationEvent;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchSetEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchSetRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import com.mannschaft.app.tournament.repository.TournamentTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 順位表の自動計算サービス。試合結果入力時に非同期で再計算する。
 * 冪等方式: 毎回全COMPLETED試合からゼロ計算してUPSERTする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandingsCalculationService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentMatchRepository matchRepository;
    private final TournamentMatchSetRepository matchSetRepository;
    private final TournamentStandingRepository standingRepository;
    private final TournamentTiebreakerRepository tiebreakerRepository;

    /**
     * 順位表再計算イベントを受信する。
     */
    @Async
    @EventListener
    @Transactional
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
        List<TournamentMatchEntity> completedMatches =
                matchRepository.findByDivisionIdAndStatus(divisionId, MatchStatus.COMPLETED);
        List<TournamentTiebreakerEntity> tiebreakers =
                tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(tournamentId);

        // 各チームの成績を集計
        Map<Long, TeamStats> statsMap = new HashMap<>();
        for (TournamentParticipantEntity p : participants) {
            statsMap.put(p.getId(), new TeamStats(p.getId()));
        }

        for (TournamentMatchEntity match : completedMatches) {
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

    private void processMatch(TournamentMatchEntity match, Map<Long, TeamStats> statsMap,
                               TournamentEntity tournament) {
        Long homeId = match.getHomeParticipantId();
        Long awayId = match.getAwayParticipantId();
        if (homeId == null || awayId == null) return;

        TeamStats homeStats = statsMap.get(homeId);
        TeamStats awayStats = statsMap.get(awayId);
        if (homeStats == null || awayStats == null) return;

        homeStats.played++;
        awayStats.played++;

        int homeScore = match.getHomeScore() != null ? match.getHomeScore() : 0;
        int awayScore = match.getAwayScore() != null ? match.getAwayScore() : 0;
        homeStats.scoreFor += homeScore;
        homeStats.scoreAgainst += awayScore;
        awayStats.scoreFor += awayScore;
        awayStats.scoreAgainst += homeScore;

        // セット別集計
        List<TournamentMatchSetEntity> sets = matchSetRepository.findByMatchIdOrderBySetNumberAsc(match.getId());
        int homeSetsWon = 0, awaySetsWon = 0;
        for (TournamentMatchSetEntity set : sets) {
            if (set.getHomeScore() > set.getAwayScore()) homeSetsWon++;
            else if (set.getAwayScore() > set.getHomeScore()) awaySetsWon++;
        }
        homeStats.setsWon += homeSetsWon;
        homeStats.setsLost += awaySetsWon;
        awayStats.setsWon += awaySetsWon;
        awayStats.setsLost += homeSetsWon;

        // 勝敗と勝点
        MatchResult result = match.getResult();
        boolean isHomeWin = result == MatchResult.HOME_WIN || result == MatchResult.FORFEIT_HOME_WIN;
        boolean isAwayWin = result == MatchResult.AWAY_WIN || result == MatchResult.FORFEIT_AWAY_WIN;
        boolean isDraw = result == MatchResult.DRAW;

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
                                                   List<TournamentMatchEntity> matches,
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

    private String calculateForm(Long participantId, List<TournamentMatchEntity> matches) {
        List<TournamentMatchEntity> teamMatches = matches.stream()
                .filter(m -> participantId.equals(m.getHomeParticipantId()) ||
                             participantId.equals(m.getAwayParticipantId()))
                .sorted(Comparator.comparing(TournamentMatchEntity::getCreatedAt).reversed())
                .limit(5)
                .toList();

        StringBuilder form = new StringBuilder();
        for (TournamentMatchEntity m : teamMatches) {
            boolean isHome = participantId.equals(m.getHomeParticipantId());
            MatchResult r = m.getResult();
            if (r == MatchResult.DRAW) {
                form.append('D');
            } else if ((isHome && (r == MatchResult.HOME_WIN || r == MatchResult.FORFEIT_HOME_WIN)) ||
                       (!isHome && (r == MatchResult.AWAY_WIN || r == MatchResult.FORFEIT_AWAY_WIN))) {
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
