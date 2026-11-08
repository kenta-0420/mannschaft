package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 取消競合の実DB試験で使う、別スロットの有効な消化・割当を生成する。 */
final class ShiftBudgetConsumptionCancelRaceTestFixture {
    private ShiftBudgetConsumptionCancelRaceTestFixture() { }

    static ShiftScheduleEntity schedule(Long teamId, ShiftScheduleStatus status) {
        return ShiftScheduleEntity.builder().teamId(teamId).title("0717取消競合")
                .periodType(ShiftPeriodType.WEEKLY).startDate(LocalDate.of(2026, 7, 1))
                .endDate(LocalDate.of(2026, 7, 7)).status(status).build();
    }

    static ShiftSlotEntity slot(Long scheduleId) {
        return ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(LocalDate.of(2026, 7, 1))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1).build();
    }

    static ShiftBudgetAllocationEntity allocation(Long orgId, Long teamId, BigDecimal consumed) {
        return ShiftBudgetAllocationEntity.builder().organizationId(orgId).teamId(teamId)
                .fiscalYearId(9171L).budgetCategoryId(9172L)
                .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                .allocatedAmount(new BigDecimal("500000")).consumedAmount(consumed)
                .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(9173L).version(0L).build();
    }

    static ShiftBudgetConsumptionEntity consumption(Long allocationId, Long scheduleId, Long slotId,
                                                     BigDecimal amount, ShiftBudgetConsumptionStatus status) {
        return ShiftBudgetConsumptionEntity.builder().allocationId(allocationId).shiftId(scheduleId)
                .slotId(slotId).userId(9173L).hourlyRateSnapshot(amount).hours(BigDecimal.ONE)
                .amount(amount).currency("JPY").status(status).recordedAt(LocalDateTime.now()).build();
    }
}
