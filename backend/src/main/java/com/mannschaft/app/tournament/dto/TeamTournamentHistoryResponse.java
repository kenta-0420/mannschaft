package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * チーム大会参加履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TeamTournamentHistoryResponse {

    private final Long teamId;
    private final List<TournamentHistoryEntry> history;

    @Getter
    @RequiredArgsConstructor
    public static class TournamentHistoryEntry {
        private final Long tournamentId;
        private final String tournamentName;
        private final String season;
        private final String divisionName;
        private final Integer finalRank;
        private final Integer played;
        private final Integer wins;
        private final Integer draws;
        private final Integer losses;
        private final Integer points;
    }
}
