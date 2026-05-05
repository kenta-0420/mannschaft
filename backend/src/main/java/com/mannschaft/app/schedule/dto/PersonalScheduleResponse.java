package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PersonalScheduleResponse {

    private final Long id;
    private final String title;
    private final String description;
    private final String location;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final Boolean allDay;
    private final String eventType;
    private final String color;
    private final String status;
    private final Long parentScheduleId;
    private final RecurrenceRuleDto recurrenceRule;
    private final Boolean isException;
    private final List<Integer> reminders;
    private final boolean googleSynced;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String createdByDisplayName;
}
