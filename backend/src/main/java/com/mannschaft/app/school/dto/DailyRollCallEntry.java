package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AbsenceReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/** 点呼1エントリ（生徒1人分）。 */
@Getter
@NoArgsConstructor
public class DailyRollCallEntry {

    /** 生徒のユーザーID。 */
    @NotNull
    private Long studentUserId;

    /** 出欠ステータス。 */
    @NotNull
    private AttendanceStatus status;

    /** 欠席理由（ABSENT/PARTIAL の場合に設定）。 */
    private AbsenceReason absenceReason;

    /** 実際の登校時刻（遅刻の場合に設定）。 */
    private LocalTime arrivalTime;

    /** 早退時刻（早退の場合に設定）。 */
    private LocalTime leaveTime;

    /** 担任コメント（最大500文字）。 */
    @Size(max = 500)
    private String comment;

    /** 保護者連絡ID（FK → family_attendance_notices.id）。 */
    private Long familyNoticeId;
}
