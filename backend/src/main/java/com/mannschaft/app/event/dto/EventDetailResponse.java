package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * イベント詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long scheduleId;
    private final String slug;
    private final String subtitle;
    private final String summary;
    private final String coverImageKey;
    private final String venueName;
    private final String venueAddress;
    private final BigDecimal venueLatitude;
    private final BigDecimal venueLongitude;
    private final String venueAccessInfo;
    private final String status;
    private final Boolean isPublic;
    private final String minRegistrationRole;
    private final LocalDateTime registrationStartsAt;
    private final LocalDateTime registrationEndsAt;
    private final Integer maxCapacity;
    private final Boolean isApprovalRequired;
    private final Long postSurveyId;
    private final Long workflowRequestId;
    private final String ogpTitle;
    private final String ogpDescription;
    private final String ogpImageKey;
    private final Integer registrationCount;
    private final Integer checkinCount;
    private final Long createdBy;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
