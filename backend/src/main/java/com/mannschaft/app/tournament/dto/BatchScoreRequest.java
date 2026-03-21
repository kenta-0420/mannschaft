package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 節内全試合スコア一括入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BatchScoreRequest {

    @NotNull
    private final List<MatchScoreEntry> scores;

    @Getter
    @RequiredArgsConstructor
    public static class MatchScoreEntry {

        @NotNull
        private final Long matchId;

        private final Integer homeScore;
        private final Integer awayScore;
        private final Integer homeExtraScore;
        private final Integer awayExtraScore;
        private final Integer homePenaltyScore;
        private final Integer awayPenaltyScore;
        private final String notes;

        @NotNull
        private final Long version;

        private final List<MatchSetRequest> sets;
    }
}
