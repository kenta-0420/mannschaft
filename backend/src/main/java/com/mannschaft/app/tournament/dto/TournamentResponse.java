package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 大会レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TournamentResponse {

    private final Long id;
    private final Long organizationId;
    private final Long templateId;
    private final Long previousTournamentId;
    private final String name;
    private final String description;
    private final String format;
    private final String season;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Integer winPoints;
    private final Integer drawPoints;
    private final Integer lossPoints;
    private final Boolean hasDraw;
    private final Boolean hasSets;
    private final Integer setsToWin;
    private final Boolean hasExtraTime;
    private final Boolean hasPenalties;
    private final String scoreUnitLabel;
    private final String bonusPointRules;
    private final String leagueRoundType;
    private final Integer knockoutLegs;
    private final String visibility;
    private final String status;
    private final Long version;
    private final Long createdBy;
    private final List<TiebreakerResponse> tiebreakers;
    private final List<StatDefResponse> statDefs;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
