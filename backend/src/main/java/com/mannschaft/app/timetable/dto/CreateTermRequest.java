package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    /** 年度。DB の academic_year は NOT NULL で既定値が意味を持たないため必須（F03.9 §学期）。 */
    @NotNull @Min(2000) @Max(2100)
    private final Integer academicYear;
    /** 年度内の表示順。省略時は DB 既定の 0。 */
    @Min(0) @Max(255)
    private final Integer sortOrder;
}
