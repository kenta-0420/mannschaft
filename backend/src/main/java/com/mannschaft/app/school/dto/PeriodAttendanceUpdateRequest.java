package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 時限出欠個別修正リクエスト DTO。
 * 教科担任または担任が既存の出欠レコードを修正する際に使用する。
 */
@Getter
@NoArgsConstructor
public class PeriodAttendanceUpdateRequest {

    /** 出欠ステータス（null の場合は変更しない）。 */
    private AttendanceStatus status;

    /** 遅刻分数（null の場合は変更しない）。 */
    private Integer lateMinutes;

    /** コメント（null の場合は変更しない）。 */
    @Size(max = 500)
    private String comment;
}
