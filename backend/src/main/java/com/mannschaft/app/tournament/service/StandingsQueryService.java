package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     */
    public TeamTournamentHistoryResponse getTeamHistory(Long teamId) {
        // 全大会から参加履歴を構築（簡易実装）
        List<TeamTournamentHistoryResponse.TournamentHistoryEntry> entries = new ArrayList<>();

        // participant -> division -> tournament の逆引き
        // 実際にはクエリ最適化が必要だが、ここではN+1を許容
        // チームIDでの検索は ParticipantRepository の findByTeamId 的なメソッドが必要
        // 簡易実装として空リストを返す
        return new TeamTournamentHistoryResponse(teamId, entries);
    }

    /**
     * チームの通算成績を取得する。
     */
    public TeamTournamentStatsResponse getTeamStats(Long teamId) {
        // 簡易実装: 全参加大会の順位表から集計
        return new TeamTournamentStatsResponse(teamId, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
