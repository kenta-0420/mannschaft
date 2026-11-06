package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class UpdateTermRequest {
    @Size(max = 100)
    private final String name;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Integer academicYear;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(255)
    private final Integer sortOrder;
}
