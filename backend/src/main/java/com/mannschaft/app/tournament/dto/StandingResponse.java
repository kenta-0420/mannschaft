package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 順位表レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StandingResponse {

    private final Long id;
    private final Long divisionId;
    private final Long participantId;
    private final Long teamId;
    private final String teamName;
    private final Integer rank;
    private final Integer played;
    private final Integer wins;
    private final Integer draws;
    private final Integer losses;
    private final Integer scoreFor;
    private final Integer scoreAgainst;
    private final Integer scoreDifference;
    private final Integer points;
    private final Integer bonusPoints;
    private final Integer setsWon;
    private final Integer setsLost;
    private final String form;
    private final String promotionZone;
    private final LocalDateTime lastCalculatedAt;
}
