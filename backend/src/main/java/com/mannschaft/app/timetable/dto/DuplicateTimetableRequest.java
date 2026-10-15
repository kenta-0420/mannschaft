package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class DuplicateTimetableRequest {
    @Size(max = 200)
    private final String name;
    private final Long targetTermId;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;
}
