package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アンケートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SurveyResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String title;
    private final String description;
    private final String status;
    private final Boolean isAnonymous;
    private final Boolean allowMultipleSubmissions;
    private final String resultsVisibility;
    private final String distributionMode;
    private final String unrespondedVisibility;
    private final Boolean autoPostToTimeline;
    private final String seriesId;
    private final String remindBeforeHours;
    private final Integer manualRemindCount;
    private final LocalDateTime startsAt;
    private final LocalDateTime expiresAt;
    private final Integer responseCount;
    private final Integer targetCount;
    private final Long createdBy;
    private final LocalDateTime publishedAt;
    private final LocalDateTime closedAt;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
