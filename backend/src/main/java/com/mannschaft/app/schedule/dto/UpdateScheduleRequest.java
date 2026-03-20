package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール更新リクエストDTO。部分更新に対応する。
 */
@Getter
@RequiredArgsConstructor
public class UpdateScheduleRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    private final Boolean allDay;

    private final String eventType;

    private final String visibility;

    private final String minViewRole;

    private final String minResponseRole;

    private final Boolean attendanceRequired;

    private final LocalDateTime attendanceDeadline;

    private final String commentOption;

    private final String updateScope;
}
