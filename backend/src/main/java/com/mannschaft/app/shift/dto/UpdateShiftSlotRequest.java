package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateShiftSlotRequest {

    private final LocalDate slotDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    private final Long positionId;

    private final Integer requiredCount;

    private final List<Long> assignedUserIds;

    @Size(max = 200)
    private final String note;
}
