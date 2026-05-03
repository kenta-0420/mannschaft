package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** 担任向け月次出欠集計レスポンス。 */
@Getter
@Builder
public class MonthlyStatisticsResponse {

    private Integer year;
    private Integer month;
    private Long teamId;
    /** distinct な attendance_date の数（授業日数）。 */
    private Integer totalSchoolDays;
    /** 対象生徒数。 */
    private Integer totalStudents;
    /** ATTENDING + PARTIAL の累計。 */
    private Integer presentCount;
    /** ABSENT の累計。 */
    private Integer absentCount;
    /** UNDECIDED の累計。 */
    private Integer undecidedCount;
    /** 全生徒・全授業日を母数とした出席率（%、小数点2桁）。 */
    private BigDecimal attendanceRate;
    /** 生徒別内訳。 */
    private List<AttendanceStatisticsSummary> studentBreakdown;
}
