package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * カルテ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateChartRecordRequest {

    private final Long staffUserId;

    @NotNull
    private final LocalDate visitDate;

    private final String chiefComplaint;

    private final String treatmentNote;

    private final String nextRecommendation;

    private final LocalDate nextVisitRecommendedDate;

    private final String allergyInfo;

    @NotNull
    private final Long version;

    private final List<CustomFieldValueRequest> customFields;
}
