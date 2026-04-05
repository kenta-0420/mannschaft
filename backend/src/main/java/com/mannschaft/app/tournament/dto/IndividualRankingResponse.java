package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 個人ランキングレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class IndividualRankingResponse {

    private final Long id;
    private final Long tournamentId;
    private final Long userId;
    private final Long participantId;
    private final String statKey;
    private final String rankingLabel;
    private final Integer rank;
    private final Integer totalValueInt;
    private final BigDecimal totalValueDecimal;
    private final LocalTime totalValueTime;
    private final Integer matchesPlayed;
    private final LocalDateTime lastCalculatedAt;
}
