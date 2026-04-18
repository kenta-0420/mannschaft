package com.mannschaft.app.event.dto;

import com.mannschaft.app.event.entity.EventAttendanceMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * イベント作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateEventRequest {

    private final Long scheduleId;

    @NotBlank
    @Size(max = 100)
    private final String slug;

    @Size(max = 200)
    private final String subtitle;

    private final String summary;

    @Size(max = 300)
    private final String coverImageKey;

    @Size(max = 200)
    private final String venueName;

    @Size(max = 500)
    private final String venueAddress;

    private final BigDecimal venueLatitude;

    private final BigDecimal venueLongitude;

    private final String venueAccessInfo;

    private final Boolean isPublic;

    @Size(max = 30)
    private final String minRegistrationRole;

    private final LocalDateTime registrationStartsAt;

    private final LocalDateTime registrationEndsAt;

    private final Integer maxCapacity;

    private final Boolean isApprovalRequired;

    /** 出席管理モード（null時はServiceでREGISTRATIONをデフォルト設定） */
    private final EventAttendanceMode attendanceMode;

    /** 事前アンケートID（任意） */
    private final Long preSurveyId;

    @Size(max = 200)
    private final String ogpTitle;

    @Size(max = 500)
    private final String ogpDescription;

    @Size(max = 300)
    private final String ogpImageKey;
}
