package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@RequiredArgsConstructor
public class TimetableResponse {
    private final Long id;
    private final String name;
    private final Long termId;
    private final String termName;
    private final String status;
    private final String visibility;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;
    private final Boolean weekPatternEnabled;
    private final LocalDate weekPatternBaseDate;
    private final String periodOverride;
    private final String notes;
    private final LocalDateTime createdAt;
}
