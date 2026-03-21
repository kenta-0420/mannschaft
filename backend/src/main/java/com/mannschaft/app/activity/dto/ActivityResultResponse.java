package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 活動記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivityResultResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final Long templateId;
    private final String templateName;
    private final String title;
    private final String description;
    private final LocalDate activityDate;
    private final String location;
    private final String visibility;
    private final String coverImageUrl;
    private final Long scheduleEventId;
    private final Integer participantCount;
    private final Integer viewCount;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
