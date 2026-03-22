package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class CreateTermRequest {
    @NotBlank @Size(max = 100)
    private final String name;
    @NotNull
    private final LocalDate startDate;
    @NotNull
    private final LocalDate endDate;
    private final Integer academicYear;
    private final Integer sortOrder;
}
