package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShiftSlotResponse {

    private final Long id;
    private final Long scheduleId;
    private final LocalDate slotDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Long positionId;
    private final String positionName;
    private final Integer requiredCount;
    private final List<Long> assignedUserIds;
    private final String note;
}
