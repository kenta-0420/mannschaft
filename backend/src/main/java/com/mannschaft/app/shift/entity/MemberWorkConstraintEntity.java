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
 * メンバー単位の任意勤務制約エンティティ（F03.5 v2 新規）。
 *
 * <p>月次労働時間・月次勤務日数・連続勤務日数・月次夜勤数・シフト間休息時間を任意に設定できる。
 * 全項目 NULL 可能（オプトイン）だが、全て NULL のレコードは DB 層 CHECK 制約で拒否される。</p>
 *
 * <p>{@code userId} が NULL の場合はチーム単位のデフォルト値として扱う。解決順序は
 * 「メンバー個別レコード → チームデフォルト → 制約なし」（詳細は設計書 §3）。</p>
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

    /** NULL の場合はチームデフォルト（全メンバー適用）。 */
    private Long userId;

    /** 月次労働時間上限（h）。NULL = 制約なし。 */
    @Column(precision = 6, scale = 2)
    private BigDecimal maxMonthlyHours;

    /** 月次勤務日数上限。NULL = 制約なし。 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxMonthlyDays;

    /** 連続勤務日数上限。NULL = 制約なし。 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxConsecutiveDays;

    /** 月次夜勤数上限。NULL = 制約なし。 */
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer maxNightShiftsPerMonth;

    /** シフト間の最低休息時間（h）。NULL = 制約なし。 */
    @Column(precision = 4, scale = 2)
    private BigDecimal minRestHoursBetweenShifts;

    @Column(length = 500)
    private String note;

    /**
     * 勤務制約を更新する。
     */
    public void updateConstraints(
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
