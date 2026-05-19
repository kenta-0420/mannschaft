package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * シフトスケジュール充足状況サマリーレスポンス（F03.5 Phase 11）。
 *
 * <p>管理者のシフト調整画面が「日付 × ポジション」のマトリクスで
 * 必要人数・確定アサイン数・希望提出数を一望するために使用する。</p>
 */
@Getter
@Builder
public class ShiftScheduleSummaryResponse {

    /** 対象スケジュール ID */
    private final Long scheduleId;

    /** 日付別の集計（昇順）。スロットが存在しない日は含まれない。 */
    private final List<DateSummary> summaryByDate;

    /** 日付ごとのサマリー。 */
    @Getter
    @Builder
    public static class DateSummary {
        /** 対象日付。 */
        private final LocalDate date;
        /** ポジション別の集計（position_id 昇順、NULL は末尾）。 */
        private final List<PositionSummary> byPosition;
        /** 当日の必要人数合計。 */
        private final Integer totalRequired;
        /** 当日の確定アサイン数合計。 */
        private final Integer totalConfirmed;
        /** 当日の希望提出延べ件数合計。 */
        private final Integer totalRequested;
    }

    /** ポジション単位の集計。 */
    @Getter
    @Builder
    public static class PositionSummary {
        /** ポジション ID（NULL = ポジション指定なし枠）。 */
        private final Long positionId;
        /** ポジション名（NULL = "ポジション指定なし"）。 */
        private final String positionName;
        /** 必要人数（requiredCount の合計）。 */
        private final Integer required;
        /** 確定アサイン数（CONFIRMED ステータスのみ）。 */
        private final Integer confirmed;
        /** 希望提出件数（preference 種別を問わず slot_date 一致分の延べ件数）。 */
        private final Integer requested;
    }
}
