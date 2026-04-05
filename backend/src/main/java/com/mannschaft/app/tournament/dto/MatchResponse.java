package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 試合レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatchResponse {

    private final Long id;
    private final Long matchdayId;
    private final Long homeParticipantId;
    private final Long awayParticipantId;
    private final Integer matchNumber;
    private final LocalDateTime scheduledDatetime;
    private final String venue;
    private final Integer homeScore;
    private final Integer awayScore;
    private final Integer homeExtraScore;
    private final Integer awayExtraScore;
    private final Integer homePenaltyScore;
    private final Integer awayPenaltyScore;
    private final Long winnerParticipantId;
    private final String result;
    private final Integer leg;
    private final Long nextMatchId;
    private final String nextMatchSlot;
    private final String notes;
    private final Long scheduleId;
    private final Long version;
    private final String status;
    private final List<MatchSetResponse> sets;
    private final List<PlayerStatResponse> playerStats;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
