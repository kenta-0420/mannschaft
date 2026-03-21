package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * カルテ詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartRecordResponse {

    private final Long id;
    private final Long teamId;
    private final Long customerUserId;
    private final String customerDisplayName;
    private final Long staffUserId;
    private final String staffDisplayName;
    private final LocalDate visitDate;
    private final String chiefComplaint;
    private final String treatmentNote;
    private final String nextRecommendation;
    private final LocalDate nextVisitRecommendedDate;
    private final String allergyInfo;
    private final Boolean isSharedToCustomer;
    private final Boolean isPinned;
    private final Long version;
    private final Map<String, Boolean> sectionsEnabled;
    private final List<CustomFieldValueResponse> customFields;
    private final List<ChartPhotoResponse> photos;
    private final List<ChartFormulaResponse> formulas;
    private final List<ChartBodyMarkResponse> bodyMarks;
    private final LocalDateTime createdAt;
}
