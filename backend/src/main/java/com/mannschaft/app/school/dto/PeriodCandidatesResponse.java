package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 時限対象生徒一覧レスポンス DTO。
 * 特定時限の出欠登録前に、対象生徒と直前時限のステータスを返す際に使用する。
 */
@Getter
@Builder
public class PeriodCandidatesResponse {

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** チームID。 */
    private Long teamId;

    /** 時限番号。 */
    private int periodNumber;

    /** 候補生徒一覧。 */
    private List<CandidateItem> candidates;

    /**
     * 候補生徒1件。直前時限のステータスを付与する。
     */
    @Getter
    @Builder
    public static class CandidateItem {

        /** 対象生徒のユーザーID。 */
        private Long studentUserId;

        /** 直前時限のステータス（登録なしの場合は null）。 */
        private AttendanceStatus previousPeriodStatus;
    }
}
