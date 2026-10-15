package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 点呼登録結果サマリ。 */
@Getter
@Builder
public class DailyRollCallSummary {

    /** 対象日。 */
    private LocalDate attendanceDate;

    /** クラスチームID。 */
    private Long teamId;

    /** 対象生徒の総数。 */
    private int totalCount;

    /** 出席（ATTENDING + PARTIAL）人数。 */
    private int presentCount;

    /** 欠席（ABSENT）人数。 */
    private int absentCount;

    /** 未確認（UNDECIDED）人数。 */
    private int undecidedCount;

    /** 点呼記録日時。 */
    private LocalDateTime recordedAt;
}
