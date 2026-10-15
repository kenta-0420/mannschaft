package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 生徒・保護者向け期間別出欠集計レスポンス。 */
@Getter
@Builder
public class StudentTermStatisticsResponse {

    private Long studentUserId;
    private LocalDate from;
    private LocalDate to;
    /** distinct な attendance_date の数（授業日数）。 */
    private Integer totalSchoolDays;
    private Integer presentDays;
    private Integer absentDays;
    /** PARTIAL かつ arrivalTime != null の件数。 */
    private Integer lateCount;
    /** PARTIAL かつ leaveTime != null の件数。 */
    private Integer earlyLeaveCount;
    /** 出席率（%、小数点2桁）。 */
    private BigDecimal attendanceRate;
    /** 教科別出席率（subjectName があるレコードのみ集計）。 */
    private List<SubjectAttendanceBreakdown> subjectBreakdown;
}
