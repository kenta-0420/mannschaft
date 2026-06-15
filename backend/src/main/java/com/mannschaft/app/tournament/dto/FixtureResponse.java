package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 試合レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class FixtureResponse {

    private Long id;
    private Long matchdayId;
    private MatchParticipantsDto participants;
    private MatchScoreDto score;
    private MatchInfoDto info;
    private List<FixtureSetResponse> sets;
    private List<PlayerStatResponse> playerStats;
    private MatchAuditDto audit;

    public record MatchParticipantsDto(
            Long homeParticipantId, Long awayParticipantId, Long winnerParticipantId) {}

    public record MatchScoreDto(
            Integer homeScore, Integer awayScore,
            Integer homeExtraScore, Integer awayExtraScore,
            Integer homePenaltyScore, Integer awayPenaltyScore) {}

    public record MatchInfoDto(
            Integer matchNumber, LocalDateTime scheduledDatetime, String venue,
            String result, Integer leg, String notes, String status,
            Long nextMatchId, String nextMatchSlot, Long scheduleId) {}

    public record MatchAuditDto(Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
