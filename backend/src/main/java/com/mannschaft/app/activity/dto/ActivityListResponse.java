package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 活動記録一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
@Builder
public class ActivityListResponse {

    private final Long id;
    private final ActivityResultResponse.TemplateInfo template;
    private final String title;
    private final LocalDate activityDate;
    private final Map<String, Object> fieldValuesSummary;
    private final int participantCount;
    private final int attachmentCount;
    private final String visibility;
    private final ActivityResultResponse.CreatedByInfo createdBy;
    private final LocalDateTime createdAt;
}
