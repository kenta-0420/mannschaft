package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class UpdateTimetableRequest {
    @Size(max = 200)
    private final String name;
    private final String visibility;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;
    private final Boolean weekPatternEnabled;
    private final LocalDate weekPatternBaseDate;
    private final String periodOverride;
    private final String notes;
}
