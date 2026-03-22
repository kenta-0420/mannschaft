package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 週間時間割ビューレスポンス。月曜〜日曜のスロット・臨時変更を含む。
 */
@Getter
@RequiredArgsConstructor
public class WeeklyViewResponse {
    private final Long timetableId;
    private final String timetableName;
    private final LocalDate weekStart;
    private final LocalDate weekEnd;
    private final Boolean weekPatternEnabled;
    private final String currentWeekPattern;
    private final List<PeriodInfo> periods;
    private final Map<String, List<SlotInfo>> days;

    /**
     * コマ情報（時限番号・ラベル・開始終了時刻・休み時間フラグ）。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PeriodInfo {
        private final Integer periodNumber;
        private final String label;
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final Boolean isBreak;
    }

    @Getter
    @RequiredArgsConstructor
    public static class SlotInfo {
        private final Integer periodNumber;
        private final String subjectName;
        private final String teacherName;
        private final String roomName;
        private final String color;
        private final String notes;
        private final Boolean isChanged;
        private final String originalSubject;
        private final String changeType;
        private final String changeReason;
    }
}
