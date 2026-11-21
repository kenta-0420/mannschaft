package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetUncountedShiftService;

import java.time.LocalDate;
import java.util.List;

/**
 * 未計上勤務の応答（CMP-260909-2152）。締め済み期間で、枠×人が計上されていない勤務。
 */
public record UncountedShiftListResponse(@JsonProperty("items") List<UncountedShiftItem> items) {

    /** 未計上勤務の1件。 */
    public record UncountedShiftItem(
            @JsonProperty("slot_id") Long slotId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("work_date") LocalDate workDate,
            @JsonProperty("shift_schedule_id") Long shiftScheduleId
    ) {
    }

    public static UncountedShiftListResponse from(List<ShiftBudgetUncountedShiftService.UncountedShift> shifts) {
        return new UncountedShiftListResponse(shifts.stream()
                .map(s -> new UncountedShiftItem(s.slotId(), s.userId(), s.workDate(), s.scheduleId()))
                .toList());
    }
}
