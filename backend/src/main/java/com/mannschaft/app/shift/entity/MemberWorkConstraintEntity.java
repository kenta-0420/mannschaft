package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * メンバー勤務制約エンティティ。チームまたはメンバー個別の勤務制約を管理する。
 * userId が NULL の場合はチームデフォルト制約を表す。
 */
@Entity
@Table(name = "member_work_constraints")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberWorkConstraintEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    /** NULL の場合はチームデフォルト制約 */
    private Long userId;

    /** 月最大勤務時間 */
    @Column(precision = 5, scale = 1)
    private BigDecimal maxMonthlyHours;

    /** 月最大勤務日数 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxMonthlyDays;

    /** 最大連続勤務日数 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxConsecutiveDays;

    /** 月最大夜勤回数 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxNightShiftsPerMonth;

    /** シフト間最低休憩時間（時間） */
    @Column(precision = 4, scale = 1)
    private BigDecimal minRestHoursBetweenShifts;

    @Column(length = 500)
    private String note;

    /**
     * 制約内容を更新する。
     */
    public void update(
            BigDecimal maxMonthlyHours,
            Integer maxMonthlyDays,
            Integer maxConsecutiveDays,
            Integer maxNightShiftsPerMonth,
            BigDecimal minRestHoursBetweenShifts,
            String note) {
        this.maxMonthlyHours = maxMonthlyHours;
        this.maxMonthlyDays = maxMonthlyDays;
        this.maxConsecutiveDays = maxConsecutiveDays;
        this.maxNightShiftsPerMonth = maxNightShiftsPerMonth;
        this.minRestHoursBetweenShifts = minRestHoursBetweenShifts;
        this.note = note;
    }
}
