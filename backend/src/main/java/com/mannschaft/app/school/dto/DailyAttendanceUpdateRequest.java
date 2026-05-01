package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AbsenceReason;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 日次出欠個別修正リクエスト。
 * null フィールドは変更しない（部分更新）。
 */
@Getter
@NoArgsConstructor
public class DailyAttendanceUpdateRequest {

    /** 出欠ステータス（null の場合は変更しない）。 */
    private AttendanceStatus status;

    /** 欠席理由（null の場合は変更しない）。 */
    private AbsenceReason absenceReason;

    /** 実際の登校時刻（null の場合は変更しない）。 */
    private LocalTime arrivalTime;

    /** 早退時刻（null の場合は変更しない）。 */
    private LocalTime leaveTime;

    /** 担任コメント（null の場合は変更しない、最大500文字）。 */
    @Size(max = 500)
    private String comment;
}
