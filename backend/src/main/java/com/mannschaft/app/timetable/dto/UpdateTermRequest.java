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
    /** 作成（{@link CreateTermRequest}）と同じ範囲。省略は可（PATCH は部分更新）。 */
    @jakarta.validation.constraints.Min(2000) @jakarta.validation.constraints.Max(2100)
    private final Integer academicYear;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(255)
    private final Integer sortOrder;
}
