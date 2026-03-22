package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.LeagueRoundType;
import com.mannschaft.app.tournament.MatchResult;
import com.mannschaft.app.tournament.MatchSlot;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.StandingsRecalculationEvent;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.CreateMatchdayRequest;
import com.mannschaft.app.tournament.dto.CreateRosterRequest;
import com.mannschaft.app.tournament.dto.MatchResponse;
import com.mannschaft.app.tournament.dto.MatchSetResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.PlayerStatBatchRequest;
import com.mannschaft.app.tournament.dto.PlayerStatRequest;
import com.mannschaft.app.tournament.dto.PlayerStatResponse;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchPlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchSetEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchPlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRosterRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchSetRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 対戦カード・スコア・出場メンバー・個人成績管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentMatchdayRepository matchdayRepository;
    private final TournamentMatchRepository matchRepository;
    private final TournamentMatchSetRepository matchSetRepository;
    private final TournamentMatchRosterRepository rosterRepository;
    private final TournamentMatchPlayerStatRepository playerStatRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentStatDefRepository statDefRepository;
    private final TournamentMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    // ===== Matchday =====

    public List<MatchdayResponse> listMatchdays(Long divisionId) {
        return matchdayRepository.findByDivisionIdOrderByMatchdayNumberAsc(divisionId)
                .stream()
                .map(md -> {
                    List<MatchResponse> matches = matchRepository.findByMatchdayIdOrderByMatchNumberAsc(md.getId())
                            .stream().map(m -> mapper.toMatchResponse(m, List.of(), List.of())).toList();
                    return mapper.toMatchdayResponse(md, matches);
                })
                .toList();
    }

    @Transactional
    public MatchdayResponse createMatchday(Long divisionId, CreateMatchdayRequest request) {
        Integer matchdayNumber = request.getMatchdayNumber();
        if (matchdayNumber == null) {
            matchdayNumber = matchdayRepository.findTopByDivisionIdOrderByMatchdayNumberDesc(divisionId)
                    .map(md -> md.getMatchdayNumber() + 1).orElse(1);
        }
        TournamentMatchdayEntity matchday = TournamentMatchdayEntity.builder()
                .divisionId(divisionId)
                .name(request.getName())
                .matchdayNumber(matchdayNumber)
                .scheduledDate(request.getScheduledDate())
                .build();
        matchday = matchdayRepository.save(matchday);
        return mapper.toMatchdayResponse(matchday, List.of());
    }

    // ===== Match Generation =====

    @Transactional
    public List<MatchdayResponse> generateMatchdays(Long tournamentId, Long divisionId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));

        List<TournamentParticipantEntity> participants =
                participantRepository.findByDivisionIdOrderBySeedAsc(divisionId);
        if (participants.size() < 2) {
            throw new BusinessException(TournamentErrorCode.INSUFFICIENT_PARTICIPANTS);
        }

        if (tournament.getFormat() == TournamentFormat.KNOCKOUT) {
            return generateKnockoutBracket(divisionId, participants);
        } else {
            return generateLeagueMatchdays(divisionId, participants, tournament.getLeagueRoundType());
        }
    }

    private List<MatchdayResponse> generateLeagueMatchdays(Long divisionId,
                                                            List<TournamentParticipantEntity> participants,
                                                            LeagueRoundType roundType) {
        int n = participants.size();
        boolean hasBye = (n % 2 != 0);
        List<TournamentParticipantEntity> teamList = new ArrayList<>(participants);
        if (hasBye) {
            teamList.add(null); // BYE用
            n = teamList.size();
        }

        int rounds = n - 1;
        List<MatchdayResponse> result = new ArrayList<>();

        for (int round = 0; round < rounds; round++) {
            TournamentMatchdayEntity matchday = matchdayRepository.save(
                    TournamentMatchdayEntity.builder()
                            .divisionId(divisionId)
                            .name("第" + (round + 1) + "節")
                            .matchdayNumber(round + 1)
                            .build());

            List<MatchResponse> matches = new ArrayList<>();
            int matchNum = 1;
            for (int i = 0; i < n / 2; i++) {
                TournamentParticipantEntity home = teamList.get(i);
                TournamentParticipantEntity away = teamList.get(n - 1 - i);
                TournamentMatchEntity match = TournamentMatchEntity.builder()
                        .matchdayId(matchday.getId())
                        .homeParticipantId(home != null ? home.getId() : null)
                        .awayParticipantId(away != null ? away.getId() : null)
                        .matchNumber(matchNum++)
                        .result(home == null || away == null ? MatchResult.BYE : MatchResult.PENDING)
                        .build();
                match = matchRepository.save(match);
                matches.add(mapper.toMatchResponse(match, List.of(), List.of()));
            }

            result.add(mapper.toMatchdayResponse(matchday, matches));

            // ラウンドロビン回転
            TournamentParticipantEntity last = teamList.remove(teamList.size() - 1);
            teamList.add(1, last);
        }

        // DOUBLE: ホーム&アウェイ入替で第2ラウンド
        if (roundType == LeagueRoundType.DOUBLE) {
            for (int round = 0; round < rounds; round++) {
                int mdNum = rounds + round + 1;
                TournamentMatchdayEntity matchday = matchdayRepository.save(
                        TournamentMatchdayEntity.builder()
                                .divisionId(divisionId)
                                .name("第" + mdNum + "節")
                                .matchdayNumber(mdNum)
                                .build());

                List<MatchResponse> matches = new ArrayList<>();
                int matchNum = 1;
                for (int i = 0; i < n / 2; i++) {
                    TournamentParticipantEntity away = teamList.get(i);
                    TournamentParticipantEntity home = teamList.get(n - 1 - i);
                    TournamentMatchEntity match = TournamentMatchEntity.builder()
                            .matchdayId(matchday.getId())
                            .homeParticipantId(home != null ? home.getId() : null)
                            .awayParticipantId(away != null ? away.getId() : null)
                            .matchNumber(matchNum++)
                            .result(home == null || away == null ? MatchResult.BYE : MatchResult.PENDING)
                            .build();
                    match = matchRepository.save(match);
                    matches.add(mapper.toMatchResponse(match, List.of(), List.of()));
                }

                result.add(mapper.toMatchdayResponse(matchday, matches));
                TournamentParticipantEntity last = teamList.remove(teamList.size() - 1);
                teamList.add(1, last);
            }
        }

        return result;
    }

    private List<MatchdayResponse> generateKnockoutBracket(Long divisionId,
                                                            List<TournamentParticipantEntity> participants) {
        int n = participants.size();
        int totalSlots = 1;
        while (totalSlots < n) totalSlots *= 2;
        int totalRounds = (int) (Math.log(totalSlots) / Math.log(2));

        List<MatchdayResponse> result = new ArrayList<>();

        // 最終戦から作成して next_match_id を設定
        List<List<TournamentMatchEntity>> roundMatches = new ArrayList<>();
        for (int round = totalRounds; round >= 1; round--) {
            int matchCount = totalSlots / (int) Math.pow(2, round);
            String roundName = switch (matchCount) {
                case 1 -> "決勝";
                case 2 -> "準決勝";
                default -> round + "回戦";
            };

            TournamentMatchdayEntity matchday = matchdayRepository.save(
                    TournamentMatchdayEntity.builder()
                            .divisionId(divisionId)
                            .name(roundName)
                            .matchdayNumber(totalRounds - round + 1)
                            .build());

            List<TournamentMatchEntity> matches = new ArrayList<>();
            for (int i = 0; i < matchCount; i++) {
                TournamentMatchEntity match = TournamentMatchEntity.builder()
                        .matchdayId(matchday.getId())
                        .matchNumber(i + 1)
                        .build();
                match = matchRepository.save(match);
                matches.add(match);
            }
            roundMatches.add(0, matches);
        }

        // next_match_id の設定
        for (int round = 0; round < roundMatches.size() - 1; round++) {
            List<TournamentMatchEntity> current = roundMatches.get(round);
            List<TournamentMatchEntity> next = roundMatches.get(round + 1);
            for (int i = 0; i < current.size(); i++) {
                TournamentMatchEntity match = current.get(i);
                match.setNextMatch(next.get(i / 2).getId(),
                        i % 2 == 0 ? MatchSlot.HOME : MatchSlot.AWAY);
                matchRepository.save(match);
            }
        }

        // 1回戦に参加チームを配置
        List<TournamentMatchEntity> firstRound = roundMatches.get(0);
        for (int i = 0; i < firstRound.size(); i++) {
            TournamentMatchEntity match = firstRound.get(i);
            Long homeId = (i * 2 < n) ? participants.get(i * 2).getId() : null;
            Long awayId = (i * 2 + 1 < n) ? participants.get(i * 2 + 1).getId() : null;
            match = match.toBuilder()
                    .homeParticipantId(homeId)
                    .awayParticipantId(awayId)
                    .result(homeId == null || awayId == null ? MatchResult.BYE : MatchResult.PENDING)
                    .build();
            matchRepository.save(match);
        }

        // レスポンス構築
        for (int round = 0; round < roundMatches.size(); round++) {
            List<TournamentMatchEntity> matches = roundMatches.get(round);
            if (!matches.isEmpty()) {
                TournamentMatchdayEntity md = matchdayRepository.findById(matches.get(0).getMatchdayId()).orElse(null);
                if (md != null) {
                    List<MatchResponse> matchResponses = matches.stream()
                            .map(m -> mapper.toMatchResponse(m, List.of(), List.of())).toList();
                    result.add(mapper.toMatchdayResponse(md, matchResponses));
                }
            }
        }

        return result;
    }

    // ===== Score =====

    public MatchResponse getMatch(Long matchId) {
        TournamentMatchEntity match = findMatchOrThrow(matchId);
        List<MatchSetResponse> sets = matchSetRepository.findByMatchIdOrderBySetNumberAsc(match.getId())
                .stream().map(mapper::toMatchSetResponse).toList();
        List<PlayerStatResponse> stats = playerStatRepository.findByMatchId(match.getId())
                .stream().map(mapper::toPlayerStatResponse).toList();
        return mapper.toMatchResponse(match, sets, stats);
    }

    @Transactional
    public MatchResponse updateScore(Long tournamentId, Long matchId, ScoreUpdateRequest request) {
        TournamentMatchEntity match = findMatchOrThrow(matchId);

        // スコアのバリデーション
        if (request.getHomeScore() != null && request.getHomeScore() < 0) {
            throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
        }
        if (request.getAwayScore() != null && request.getAwayScore() < 0) {
            throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
        }

        // 結果判定
        MatchResult result = determineResult(request, match);

        Long winnerId = null;
        if (result == MatchResult.HOME_WIN || result == MatchResult.FORFEIT_HOME_WIN) {
            winnerId = match.getHomeParticipantId();
        } else if (result == MatchResult.AWAY_WIN || result == MatchResult.FORFEIT_AWAY_WIN) {
            winnerId = match.getAwayParticipantId();
        }

        match.updateScore(request.getHomeScore(), request.getAwayScore(),
                request.getHomeExtraScore(), request.getAwayExtraScore(),
                request.getHomePenaltyScore(), request.getAwayPenaltyScore(),
                winnerId, result, request.getNotes());
        matchRepository.save(match);

        // セット別スコアの保存
        if (request.getSets() != null) {
            matchSetRepository.deleteByMatchId(matchId);
            request.getSets().forEach(setReq -> matchSetRepository.save(
                    TournamentMatchSetEntity.builder()
                            .matchId(matchId)
                            .setNumber(setReq.getSetNumber())
                            .homeScore(setReq.getHomeScore())
                            .awayScore(setReq.getAwayScore())
                            .build()));
        }

        // ディビジョンIDを取得して順位表再計算イベント発火
        TournamentMatchdayEntity matchday = matchdayRepository.findById(match.getMatchdayId()).orElse(null);
        if (matchday != null) {
            eventPublisher.publishEvent(
                    new StandingsRecalculationEvent(this, matchday.getDivisionId(), tournamentId));
        }

        return getMatch(matchId);
    }

    @Transactional
    public void batchUpdateScores(Long tournamentId, Long divisionId, Long matchdayId,
                                  BatchScoreRequest request) {
        for (BatchScoreRequest.MatchScoreEntry entry : request.getScores()) {
            TournamentMatchEntity match = findMatchOrThrow(entry.getMatchId());
            ScoreUpdateRequest scoreReq = new ScoreUpdateRequest(
                    entry.getHomeScore(), entry.getAwayScore(),
                    entry.getHomeExtraScore(), entry.getAwayExtraScore(),
                    entry.getHomePenaltyScore(), entry.getAwayPenaltyScore(),
                    entry.getNotes(), entry.getVersion(), entry.getSets());
            MatchResult result = determineResult(scoreReq, match);
            Long winnerId = null;
            if (result == MatchResult.HOME_WIN) winnerId = match.getHomeParticipantId();
            else if (result == MatchResult.AWAY_WIN) winnerId = match.getAwayParticipantId();

            match.updateScore(entry.getHomeScore(), entry.getAwayScore(),
                    entry.getHomeExtraScore(), entry.getAwayExtraScore(),
                    entry.getHomePenaltyScore(), entry.getAwayPenaltyScore(),
                    winnerId, result, entry.getNotes());
            matchRepository.save(match);

            if (entry.getSets() != null) {
                matchSetRepository.deleteByMatchId(entry.getMatchId());
                entry.getSets().forEach(setReq -> matchSetRepository.save(
                        TournamentMatchSetEntity.builder()
                                .matchId(entry.getMatchId())
                                .setNumber(setReq.getSetNumber())
                                .homeScore(setReq.getHomeScore())
                                .awayScore(setReq.getAwayScore())
                                .build()));
            }
        }

        // 順位表再計算イベントは1回だけ発火
        eventPublisher.publishEvent(new StandingsRecalculationEvent(this, divisionId, tournamentId));
    }

    @Transactional
    public void changeMatchStatus(Long matchId, MatchStatus newStatus) {
        TournamentMatchEntity match = findMatchOrThrow(matchId);
        match.changeStatus(newStatus);
        matchRepository.save(match);
    }

    // ===== Roster =====

    public List<RosterResponse> listRosters(Long matchId) {
        return rosterRepository.findByMatchIdOrderByParticipantIdAscJerseyNumberAsc(matchId)
                .stream().map(mapper::toRosterResponse).toList();
    }

    @Transactional
    public List<RosterResponse> createRosters(Long matchId, CreateRosterRequest request) {
        List<TournamentMatchRosterEntity> rosters = request.getEntries().stream()
                .map(entry -> TournamentMatchRosterEntity.builder()
                        .matchId(matchId)
                        .participantId(entry.getParticipantId())
                        .userId(entry.getUserId())
                        .isStarter(entry.getIsStarter() != null ? entry.getIsStarter() : true)
                        .jerseyNumber(entry.getJerseyNumber())
                        .position(entry.getPosition())
                        .build())
                .toList();
        return rosterRepository.saveAll(rosters).stream()
                .map(mapper::toRosterResponse).toList();
    }

    @Transactional
    public void deleteRoster(Long rosterId) {
        rosterRepository.deleteById(rosterId);
    }

    // ===== Player Stats =====

    @Transactional
    public MatchResponse updatePlayerStats(Long tournamentId, Long matchId,
                                           PlayerStatBatchRequest request) {
        TournamentMatchEntity match = findMatchOrThrow(matchId);

        for (PlayerStatRequest stat : request.getStats()) {
            // stat_key のバリデーション
            statDefRepository.findByTournamentIdAndStatKey(tournamentId, stat.getStatKey())
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.INVALID_STAT_KEY));

            TournamentMatchPlayerStatEntity existing =
                    playerStatRepository.findByMatchIdAndUserIdAndStatKey(matchId, stat.getUserId(), stat.getStatKey())
                            .orElse(null);

            if (existing != null) {
                existing.updateValue(
                        stat.getValueInt(),
                        stat.getValueDecimal(),
                        stat.getValueTime() != null ? LocalTime.parse(stat.getValueTime()) : null);
                playerStatRepository.save(existing);
            } else {
                playerStatRepository.save(TournamentMatchPlayerStatEntity.builder()
                        .matchId(matchId)
                        .participantId(stat.getParticipantId())
                        .userId(stat.getUserId())
                        .statKey(stat.getStatKey())
                        .valueInt(stat.getValueInt())
                        .valueDecimal(stat.getValueDecimal())
                        .valueTime(stat.getValueTime() != null ? LocalTime.parse(stat.getValueTime()) : null)
                        .build());
            }
        }

        // 個人ランキング再計算イベント発火
        eventPublisher.publishEvent(new RankingsRecalculationEvent(this, tournamentId));

        return getMatch(matchId);
    }

    // ===== Private =====

    private MatchResult determineResult(ScoreUpdateRequest request, TournamentMatchEntity match) {
        if (request.getHomeScore() == null || request.getAwayScore() == null) {
            return MatchResult.PENDING;
        }

        int totalHome = request.getHomeScore();
        int totalAway = request.getAwayScore();

        if (request.getHomeExtraScore() != null) totalHome += request.getHomeExtraScore();
        if (request.getAwayExtraScore() != null) totalAway += request.getAwayExtraScore();

        if (totalHome > totalAway) return MatchResult.HOME_WIN;
        if (totalAway > totalHome) return MatchResult.AWAY_WIN;

        // PK戦
        if (request.getHomePenaltyScore() != null && request.getAwayPenaltyScore() != null) {
            if (request.getHomePenaltyScore() > request.getAwayPenaltyScore()) return MatchResult.HOME_WIN;
            if (request.getAwayPenaltyScore() > request.getHomePenaltyScore()) return MatchResult.AWAY_WIN;
        }

        return MatchResult.DRAW;
    }

    private TournamentMatchEntity findMatchOrThrow(Long matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));
    }
}
