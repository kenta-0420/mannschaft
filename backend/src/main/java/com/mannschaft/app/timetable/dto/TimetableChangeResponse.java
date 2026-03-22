package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@RequiredArgsConstructor
public class TimetableChangeResponse {
    private final Long id;
    private final LocalDate targetDate;
    private final Integer periodNumber;
    private final String changeType;
    private final String subjectName;
    private final String teacherName;
    private final String roomName;
    private final String reason;
    private final Boolean notified;
    private final LocalDateTime createdAt;
}
