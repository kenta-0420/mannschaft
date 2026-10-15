package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュール更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateShiftScheduleRequest {

    @Size(max = 200)
    private final String title;

    private final String periodType;

    private final LocalDate startDate;

    private final LocalDate endDate;

    private final String status;

    private final LocalDateTime requestDeadline;

    @Size(max = 5000)
    private final String note;
}
