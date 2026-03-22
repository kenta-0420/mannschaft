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
    private final Integer sortOrder;
}
