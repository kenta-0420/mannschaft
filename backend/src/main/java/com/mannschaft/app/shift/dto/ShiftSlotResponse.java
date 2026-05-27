package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ShiftSlotResponse {

    Long id;
    Long scheduleId;

    ShiftSlotTimeDto     time;      // slotDate, startTime, endTime
    ShiftSlotPositionDto position;  // positionId, positionName, requiredCount
    List<Long>           assignedUserIds;
    String               note;

    public record ShiftSlotTimeDto(LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}
    public record ShiftSlotPositionDto(Long positionId, String positionName, Integer requiredCount) {}
}
