package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * カルテ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateChartRecordRequest {

    @NotNull
    private final Long customerUserId;

    private final Long staffUserId;

    @NotNull
    private final LocalDate visitDate;

    private final String chiefComplaint;

    private final String treatmentNote;

    private final String nextRecommendation;

    private final LocalDate nextVisitRecommendedDate;

    private final String allergyInfo;

    private final Boolean isSharedToCustomer;

    private final Long templateId;

    private final List<CustomFieldValueRequest> customFields;
}
