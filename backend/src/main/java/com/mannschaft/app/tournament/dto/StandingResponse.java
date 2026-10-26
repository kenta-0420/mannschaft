package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 順位表レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class StandingResponse {

    private Long id;
    private StandingMetaDto meta;
    private StandingTeamDto team;
    private StandingRecordDto record;
    private StandingScoreDto score;
    private String form;
    private StandingStatusDto status;

    public record StandingMetaDto(Long divisionId, Long participantId) {}

    public record StandingTeamDto(Long teamId, String teamName, Integer rank) {}

    public record StandingRecordDto(Integer played, Integer wins, Integer draws, Integer losses) {}

    public record StandingScoreDto(
            Integer scoreFor, Integer scoreAgainst, Integer scoreDifference,
            Integer points, Integer bonusPoints,
            Integer setsWon, Integer setsLost) {}

    public record StandingStatusDto(String promotionZone, LocalDateTime lastCalculatedAt) {}
}
