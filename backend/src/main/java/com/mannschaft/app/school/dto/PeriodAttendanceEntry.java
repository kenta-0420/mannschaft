package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 時限出欠1エントリ DTO。
 * 一括登録リクエストの entries 配列の各要素として使用する。
 */
@Getter
@NoArgsConstructor
public class PeriodAttendanceEntry {

    /** 対象生徒のユーザーID。 */
    @NotNull
    private Long studentUserId;

    /** 出欠ステータス。 */
    @NotNull
    private AttendanceStatus status;

    /** 遅刻分数（PARTIAL 時）。 */
    private Integer lateMinutes;

    /** 教科担任コメント（最大500文字）。 */
    @Size(max = 500)
    private String comment;
}
