package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class CreateTimetableRequest {
    @NotBlank @Size(max = 200)
    private final String name;
    @NotNull
    private final Long termId;
    @NotNull
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;
    private final String visibility;  // MEMBERS_ONLY, PUBLIC
    private final Boolean weekPatternEnabled;
    private final LocalDate weekPatternBaseDate;
    private final String periodOverride;  // JSON
    private final String notes;
}
