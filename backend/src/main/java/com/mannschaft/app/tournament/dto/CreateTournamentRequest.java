package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 大会作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTournamentRequest {

    private final Long templateId;

    @NotBlank @Size(max = 200)
    private final String name;

    private final String description;

    @NotNull
    private final String format;

    // F08.10 多競技対応（🟡-1a）: 競技種別。任意（未指定は SOCCER 既定）。
    // match.domain.Sport の 8 値のみ許容（不正値は valueOf 前に 400 で弾く）。
    @Pattern(regexp = "SOCCER|FUTSAL|BASKETBALL|VOLLEYBALL|SHOGI|GO|FIGURE_SKATING|GYMNASTICS",
            message = "sport が不正な値です")
    private final String sport;

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

    // F08.7 順位UI Wave0: 6 値の enum 名のみ許容（不正値は valueOf 前に 400 で弾く）。
    @Pattern(regexp = "PUBLIC|SUPPORTERS_AND_ABOVE|MEMBERS_AND_ABOVE|ADMINS_AND_ABOVE"
            + "|SCOPE_AFFILIATED|PARTICIPANTS_ONLY",
            message = "visibility が不正な値です")
    private final String visibility;
    private final List<TiebreakerRequest> tiebreakers;
    private final List<StatDefRequest> statDefs;
}
