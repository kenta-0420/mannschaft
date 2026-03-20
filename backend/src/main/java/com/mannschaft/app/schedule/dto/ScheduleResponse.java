package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール一覧用レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScheduleResponse {

    private final Long id;
    private final String title;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final Boolean allDay;
    private final String eventType;
    private final String status;
    private final Boolean attendanceRequired;
    private final String location;
    private final LocalDateTime createdAt;
}
