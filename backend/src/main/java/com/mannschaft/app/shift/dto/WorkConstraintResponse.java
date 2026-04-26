package com.mannschaft.app.shift.dto;

import java.math.BigDecimal;

/**
 * 勤務制約レスポンスDTO。
 *
 * @param id                        制約ID
 * @param teamId                    チームID
 * @param userId                    ユーザーID（null の場合はチームデフォルト制約）
 * @param maxMonthlyHours           月最大勤務時間
 * @param maxMonthlyDays            月最大勤務日数
 * @param maxConsecutiveDays        最大連続勤務日数
 * @param maxNightShiftsPerMonth    月最大夜勤回数
 * @param minRestHoursBetweenShifts シフト間最低休憩時間（時間）
 * @param note                      備考
 */
public record WorkConstraintResponse(
        Long id,
        Long teamId,
        Long userId,
        BigDecimal maxMonthlyHours,
        Integer maxMonthlyDays,
        Integer maxConsecutiveDays,
        Integer maxNightShiftsPerMonth,
        BigDecimal minRestHoursBetweenShifts,
        String note
) {}
