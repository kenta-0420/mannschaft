package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * チーム大会参加履歴レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TeamTournamentHistoryResponse {

    private Long teamId;
    private List<TournamentHistoryEntry> history;

    @Builder(toBuilder = true)
    @Getter
    public static class TournamentHistoryEntry {
        private Long organizationId;
        private TournamentHistoryEntryMeta meta;
        private TournamentHistoryEntryIdentifiers identifiers;
        private TournamentHistoryEntryRecord record;

        public record TournamentHistoryEntryMeta(
                String tournamentName, String season, String divisionName, Integer finalRank) {}

        public record TournamentHistoryEntryIdentifiers(
                Long tournamentId, Long divisionId, Long participantId) {}

        public record TournamentHistoryEntryRecord(
                Integer played, Integer wins, Integer draws, Integer losses, Integer points) {}
    }
}
