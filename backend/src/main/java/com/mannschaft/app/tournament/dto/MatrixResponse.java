package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 対戦マトリクスレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatrixResponse {

    private final List<ParticipantSummary> participants;
    private final Map<String, MatrixCell> cells;

    @Getter
    @RequiredArgsConstructor
    public static class ParticipantSummary {
        private final Long participantId;
        private final Long teamId;
        private final String teamName;
    }

    @Getter
    @RequiredArgsConstructor
    public static class MatrixCell {
        private final Long matchId;
        private final Integer homeScore;
        private final Integer awayScore;
        private final String result;
    }
}
