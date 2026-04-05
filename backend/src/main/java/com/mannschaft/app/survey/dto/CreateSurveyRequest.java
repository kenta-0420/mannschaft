package com.mannschaft.app.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アンケート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSurveyRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @NotNull
    private final Boolean isAnonymous;

    @NotNull
    private final Boolean allowMultipleSubmissions;

    @NotBlank
    private final String resultsVisibility;

    @NotBlank
    private final String distributionMode;

    private final Boolean autoPostToTimeline;

    @Size(max = 50)
    private final String seriesId;

    private final List<Integer> remindBeforeHours;

    private final LocalDateTime startsAt;

    private final LocalDateTime expiresAt;

    @Valid
    private final List<CreateQuestionRequest> questions;

    private final List<Long> targetUserIds;

    private final List<Long> resultViewerUserIds;
}
