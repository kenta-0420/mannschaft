package com.mannschaft.app.survey.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アンケート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSurveyRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    private final Boolean isAnonymous;

    private final Boolean allowMultipleSubmissions;

    private final String resultsVisibility;

    /**
     * 未回答者一覧の公開範囲。HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS。
     */
    private final String unrespondedVisibility;

    private final Boolean autoPostToTimeline;

    private final List<Integer> remindBeforeHours;

    private final LocalDateTime startsAt;

    private final LocalDateTime expiresAt;
}
