package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 順位表・対戦マトリクス・チーム成績の参照サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StandingsQueryService {

    private final TournamentStandingRepository standingRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentMatchRepository matchRepository;
    private final TournamentMapper mapper;

    /**
     * 順位表を取得する。
     */
    public List<StandingResponse> getStandings(Long divisionId) {
        List<TournamentStandingEntity> standings = standingRepository.findByDivisionIdOrderByRankAsc(divisionId);
        return standings.stream().map(s -> {
            TournamentParticipantEntity participant = participantRepository.findById(s.getParticipantId()).orElse(null);
            Long teamId = participant != null ? participant.getTeamId() : null;
            String teamName = participant != null && participant.getDisplayName() != null
                    ? participant.getDisplayName() : "Team " + teamId;
            return mapper.toStandingResponse(s, teamId, teamName);
        }).toList();
    }

    /**
     * 対戦マトリクスを取得する。
     */
    public MatrixResponse getMatrix(Long divisionId) {
        List<TournamentParticipantEntity> participants =
                participantRepository.findByDivisionIdOrderBySeedAsc(divisionId);
        List<TournamentMatchEntity> matches = matchRepository.findByDivisionId(divisionId);

        List<MatrixResponse.ParticipantSummary> summaries = participants.stream()
                .map(p -> new MatrixResponse.ParticipantSummary(
                        p.getId(), p.getTeamId(),
                        p.getDisplayName() != null ? p.getDisplayName() : "Team " + p.getTeamId()))
                .toList();

        Map<String, MatrixResponse.MatrixCell> cells = new HashMap<>();
        for (TournamentMatchEntity match : matches) {
            if (match.getHomeParticipantId() != null && match.getAwayParticipantId() != null) {
                String key = match.getHomeParticipantId() + "_" + match.getAwayParticipantId();
                cells.put(key, new MatrixResponse.MatrixCell(
                        match.getId(), match.getHomeScore(), match.getAwayScore(),
                        match.getResult().name()));
            }
        }

        return new MatrixResponse(summaries, cells);
    }

    /**
     * チームの大会参加履歴を取得する。
     * <p>
     * TODO: N+1改善候補（将来はJOINクエリへの移行を検討）
     */
    public TeamTournamentHistoryResponse getTeamHistory(Long teamId) {
        List<TournamentParticipantEntity> participants =
                participantRepository.findAllByTeamId(teamId);

        List<TeamTournamentHistoryResponse.TournamentHistoryEntry> entries = new ArrayList<>();
        for (TournamentParticipantEntity p : participants) {
            // divisionId → division → tournament の逆引き
            var divisionOpt = divisionRepository.findById(p.getDivisionId());
            if (divisionOpt.isEmpty()) continue;
            var division = divisionOpt.get();

            var tournamentOpt = tournamentRepository.findById(division.getTournamentId());
            if (tournamentOpt.isEmpty()) continue;
            var tournament = tournamentOpt.get();

            // 順位表から成績取得（なければゼロ）
            var standingOpt = standingRepository.findByDivisionIdAndParticipantId(
                    p.getDivisionId(), p.getId());

            Integer finalRank = standingOpt.map(TournamentStandingEntity::getRank).orElse(null);
            int played = standingOpt.map(TournamentStandingEntity::getPlayed).orElse(0);
            int wins   = standingOpt.map(TournamentStandingEntity::getWins).orElse(0);
            int draws  = standingOpt.map(TournamentStandingEntity::getDraws).orElse(0);
            int losses = standingOpt.map(TournamentStandingEntity::getLosses).orElse(0);
            int points = standingOpt.map(TournamentStandingEntity::getPoints).orElse(0);

            entries.add(TeamTournamentHistoryResponse.TournamentHistoryEntry.builder()
                    .organizationId(tournament.getOrganizationId())
                    .meta(new TeamTournamentHistoryResponse.TournamentHistoryEntry.TournamentHistoryEntryMeta(
                            tournament.getName(), tournament.getSeason(),
                            division.getName(), finalRank))
                    .identifiers(new TeamTournamentHistoryResponse.TournamentHistoryEntry.TournamentHistoryEntryIdentifiers(
                            tournament.getId(), division.getId(), p.getId()))
                    .record(new TeamTournamentHistoryResponse.TournamentHistoryEntry.TournamentHistoryEntryRecord(
                            played, wins, draws, losses, points))
                    .build());
        }
        return TeamTournamentHistoryResponse.builder()
                .teamId(teamId)
                .history(entries)
                .build();
    }

    /**
     * チームの通算成績を取得する。
     * <p>
     * TODO: N+1改善候補（将来はJOINクエリへの移行を検討）
     */
    public TeamTournamentStatsResponse getTeamStats(Long teamId) {
        List<TournamentParticipantEntity> participants =
                participantRepository.findAllByTeamId(teamId);

        int totalMatches = 0;
        int wins = 0, draws = 0, losses = 0;
        int goalsFor = 0, goalsAgainst = 0;
        Integer bestRank = null; // 最高順位（数値が小さいほど上位）

        // 大会数はユニークなtournamentIdで数える
        Set<Long> tournamentIds = new HashSet<>();

        for (TournamentParticipantEntity p : participants) {
            var standingOpt = standingRepository.findByDivisionIdAndParticipantId(
                    p.getDivisionId(), p.getId());
            if (standingOpt.isEmpty()) continue;

            var s = standingOpt.get();
            // divisionId → tournament を逆引きして重複排除
            var divisionOpt = divisionRepository.findById(p.getDivisionId());
            if (divisionOpt.isPresent()) {
                tournamentIds.add(divisionOpt.get().getTournamentId());
            }
            totalMatches += s.getPlayed();
            wins         += s.getWins();
            draws        += s.getDraws();
            losses       += s.getLosses();
            goalsFor     += s.getScoreFor();
            goalsAgainst += s.getScoreAgainst();
            if (s.getRank() != null && (bestRank == null || s.getRank() < bestRank)) {
                bestRank = s.getRank();
            }
        }
        int totalTournaments = tournamentIds.size();

        return new TeamTournamentStatsResponse(
                teamId, totalTournaments, totalMatches,
                wins, draws, losses, goalsFor, goalsAgainst, bestRank);
    }
}
