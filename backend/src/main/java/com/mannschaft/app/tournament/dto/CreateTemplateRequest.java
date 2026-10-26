package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTemplateRequest {

    @NotBlank @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 30)
    private final String icon;

    @NotNull
    private final String supportedFormats;

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
    private final List<TiebreakerRequest> tiebreakers;
    private final List<StatDefRequest> statDefs;
}
