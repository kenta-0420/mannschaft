package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 大会更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTournamentRequest {

    @Size(max = 200)
    private final String name;

    private final String description;
    private final String format;

    @Size(max = 50)
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

    @Size(max = 20)
    private final String scoreUnitLabel;

    private final String bonusPointRules;
    private final String leagueRoundType;
    private final Integer knockoutLegs;
    private final String visibility;

    @NotNull
    private final Long version;

    private final List<TiebreakerRequest> tiebreakers;
    private final List<StatDefRequest> statDefs;
}
