package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * メンバー勤務制約レスポンス DTO（F03.5 v2 新規）。
 *
 * <p>{@code userId} が {@code null} のレコードはチームデフォルト（全メンバー適用）を示す。
 * 個別オーバーライド or デフォルトの判別は {@link #getUserId()} の NULL 有無で行う。</p>
 */
@Getter
@RequiredArgsConstructor
public class MemberWorkConstraintResponse {

    private final Long id;
    private final Long teamId;

    /** NULL = チームデフォルト。 */
    private final Long userId;

    private final BigDecimal maxMonthlyHours;
    private final Integer maxMonthlyDays;
    private final Integer maxConsecutiveDays;
    private final Integer maxNightShiftsPerMonth;
    private final BigDecimal minRestHoursBetweenShifts;
    private final String note;
}
