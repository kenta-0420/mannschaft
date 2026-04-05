package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@RequiredArgsConstructor
public class TimetableTermResponse {
    private final Long id;
    private final String name;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Integer academicYear;
    private final String scope;  // ORGANIZATION or TEAM
    private final LocalDateTime createdAt;
}
