package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateResponse {

    private final Long id;
    private final Long organizationId;
    private final Long sourcePresetId;
    private final String name;
    private final String description;
    private final String icon;
    private final String supportedFormats;
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
    private final Long version;
    private final Long createdBy;
    private final List<TiebreakerResponse> tiebreakers;
    private final List<StatDefResponse> statDefs;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
