package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 時限出欠一覧レスポンス DTO。
 * 特定日・特定時限の全生徒の出欠レコードを返す際に使用する。
 */
@Getter
@Builder
public class PeriodAttendanceListResponse {

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** チームID。 */
    private Long teamId;

    /** 時限番号。 */
    private int periodNumber;

    /** 出欠レコード一覧。 */
    private List<PeriodAttendanceResponse> records;

    /** 出席人数（ATTENDING + PARTIAL）。 */
    private int presentCount;

    /** 欠席人数（ABSENT）。 */
    private int absentCount;
}
