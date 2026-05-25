package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 個人ランキングレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class IndividualRankingResponse {

    private Long id;
    private IndividualRankingContextDto context;
    private IndividualRankingStatDto stat;
    private Integer rank;
    private LocalDateTime lastCalculatedAt;

    public record IndividualRankingContextDto(
            Long tournamentId, Long userId, Long participantId, Integer matchesPlayed) {}

    public record IndividualRankingStatDto(
            String statKey, String rankingLabel,
            Integer totalValueInt, BigDecimal totalValueDecimal, LocalTime totalValueTime) {}
}
