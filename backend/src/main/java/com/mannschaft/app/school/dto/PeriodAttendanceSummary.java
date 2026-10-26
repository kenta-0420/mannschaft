package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 時限出欠登録結果サマリ DTO。
 * submitPeriodAttendance の戻り値として使用する。
 */
@Getter
@Builder
public class PeriodAttendanceSummary {

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** チームID。 */
    private Long teamId;

    /** 時限番号。 */
    private int periodNumber;

    /** 登録対象の総生徒数。 */
    private int totalCount;

    /** 出席人数（ATTENDING + PARTIAL）。 */
    private int presentCount;

    /** 欠席人数（ABSENT）。 */
    private int absentCount;

    /** 新規検知アラート数。 */
    private int alertCount;

    /** 登録日時。 */
    private LocalDateTime recordedAt;
}
