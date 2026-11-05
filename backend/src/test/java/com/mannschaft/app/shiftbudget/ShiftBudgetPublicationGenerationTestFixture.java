package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** 公開世代の試練で使う、有効な利用者・シフト・割当・時給を組み立てる。 */
final class ShiftBudgetPublicationGenerationTestFixture {
    static final LocalDate SLOT_DATE = LocalDate.of(2026, 7, 1);

    private ShiftBudgetPublicationGenerationTestFixture() { }

    static UserEntity user(String nonce, String label) {
        return UserEntity.builder().email("0718-" + label + "-" + nonce + "@example.com")
                .lastName("公開世代").firstName(label).displayName("0718 " + label)
                .isSearchable(true).locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).build();
    }

    static ShiftScheduleEntity schedule(Long teamId, Long actorId) {
        return ShiftScheduleEntity.builder().teamId(teamId).title("0718 公開世代")
                .periodType(ShiftPeriodType.WEEKLY).startDate(SLOT_DATE)
                .endDate(SLOT_DATE.plusDays(6)).status(ShiftScheduleStatus.ADJUSTING)
                .createdBy(actorId).build();
    }

    static ShiftSlotEntity slot(Long scheduleId, Long userId) {
        return ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(SLOT_DATE)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0))
                .requiredCount(1).assignedUserIds("[" + userId + "]").build();
    }

    static ShiftBudgetAllocationEntity allocation(Long organizationId, Long teamId, Long actorId) {
        return ShiftBudgetAllocationEntity.builder().organizationId(organizationId).teamId(teamId)
                .fiscalYearId(9178L).budgetCategoryId(9179L)
                .periodStart(SLOT_DATE).periodEnd(SLOT_DATE.withDayOfMonth(31))
                .allocatedAmount(new BigDecimal("500000.00")).consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(actorId).version(0L).build();
    }

    static ShiftHourlyRateEntity rate(Long teamId, Long userId, BigDecimal hourlyRate) {
        return ShiftHourlyRateEntity.builder().teamId(teamId).userId(userId)
                .hourlyRate(hourlyRate).effectiveFrom(SLOT_DATE).build();
    }
}
