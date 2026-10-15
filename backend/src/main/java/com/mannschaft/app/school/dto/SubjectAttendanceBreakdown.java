package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** 教科別出席率（期間別集計の内訳）。 */
@Getter
@Builder
public class SubjectAttendanceBreakdown {

    private String subjectName;
    private Integer totalPeriods;
    private Integer presentPeriods;
    /** 出席率（%、小数点2桁）。 */
    private BigDecimal attendanceRate;
}
