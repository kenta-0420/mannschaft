package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * シフト希望提出リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateShiftRequestRequest {

    @NotNull
    private final Long scheduleId;

    private final Long slotId;

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final String preference;

    @Size(max = 200)
    private final String note;
}
