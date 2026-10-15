package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** 生徒単位の出欠統計サマリー（月次・期間別集計の内訳）。 */
@Getter
@Builder
public class AttendanceStatisticsSummary {

    private Long studentUserId;
    private Integer presentDays;
    private Integer absentDays;
    /** PARTIAL かつ arrivalTime != null の件数。 */
    private Integer lateCount;
    /** PARTIAL かつ leaveTime != null の件数。 */
    private Integer earlyLeaveCount;
    /** 出席率（%、小数点2桁）。 */
    private BigDecimal attendanceRate;
}
