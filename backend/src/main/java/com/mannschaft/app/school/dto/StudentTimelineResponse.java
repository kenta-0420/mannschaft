package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 生徒の1日タイムラインレスポンス DTO。
 * 特定生徒の特定日の全時限出欠を時限順に返す際に使用する。
 */
@Getter
@Builder
public class StudentTimelineResponse {

    /** 対象生徒のユーザーID。 */
    private Long studentUserId;

    /** 対象日。 */
    private LocalDate attendanceDate;

    /** 時限別出欠レコード一覧（時限番号昇順）。 */
    private List<PeriodAttendanceResponse> periods;
}
