package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** 日次出欠一覧レスポンス。 */
@Getter
@Builder
public class DailyAttendanceListResponse {

    /** 対象日。 */
    private LocalDate attendanceDate;

    /** クラスチームID。 */
    private Long teamId;

    /** 出欠レコード一覧。 */
    private List<DailyAttendanceResponse> records;

    /** 対象生徒の総数。 */
    private int totalCount;

    /** 出席（ATTENDING + PARTIAL）人数。 */
    private int presentCount;

    /** 欠席（ABSENT）人数。 */
    private int absentCount;

    /** 未確認（UNDECIDED）人数。 */
    private int undecidedCount;
}
