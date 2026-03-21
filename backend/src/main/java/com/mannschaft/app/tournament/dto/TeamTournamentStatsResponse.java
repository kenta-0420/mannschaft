package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム通算成績レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TeamTournamentStatsResponse {

    private final Long teamId;
    private final Integer totalTournaments;
    private final Integer totalPlayed;
    private final Integer totalWins;
    private final Integer totalDraws;
    private final Integer totalLosses;
    private final Integer totalScoreFor;
    private final Integer totalScoreAgainst;
    private final Integer bestRank;
}
