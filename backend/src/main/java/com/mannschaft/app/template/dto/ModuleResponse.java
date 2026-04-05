package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * モジュール詳細レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ModuleResponse {

    private final Long id;
    private final String name;
    private final String slug;
    private final String description;
    private final String moduleType;
    private final Integer moduleNumber;
    private final Boolean requiresPaidPlan;
    private final Integer trialDays;
    private final Boolean isActive;
    private final List<LevelAvailabilityResponse> levelAvailability;
    private final List<ModuleSummaryResponse> recommendations;
}
