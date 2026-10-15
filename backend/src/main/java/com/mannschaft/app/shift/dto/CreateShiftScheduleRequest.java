package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュール作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateShiftScheduleRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String periodType;

    @NotNull
    private final LocalDate startDate;

    @NotNull
    private final LocalDate endDate;

    private final LocalDateTime requestDeadline;

    @Size(max = 5000)
    private final String note;
}
