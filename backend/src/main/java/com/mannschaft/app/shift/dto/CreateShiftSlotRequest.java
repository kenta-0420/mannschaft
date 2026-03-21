package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * シフト枠作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateShiftSlotRequest {

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    private final Long positionId;

    private final Integer requiredCount;

    @Size(max = 200)
    private final String note;
}
