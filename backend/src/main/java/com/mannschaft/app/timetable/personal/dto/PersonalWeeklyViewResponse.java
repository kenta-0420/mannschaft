package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * F03.15 Phase 2 個人時間割の週間ビューレスポンス。
 *
 * <p>Phase 2 ではリンク先チームの臨時変更反映は未実装。コマと時限定義のみを返す。</p>
 */
public record PersonalWeeklyViewResponse(
        @JsonProperty("personal_timetable_id") Long personalTimetableId,
        @JsonProperty("personal_timetable_name") String personalTimetableName,
        @JsonProperty("week_start") LocalDate weekStart,
        @JsonProperty("week_end") LocalDate weekEnd,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("current_week_pattern") String currentWeekPattern,
        List<PersonalTimetablePeriodResponse> periods,
        /** キー: MON/TUE/WED/THU/FRI/SAT/SUN */
        Map<String, WeeklyDayInfo> days) {

    /**
     * 曜日ごとの日次情報。
     */
    public record WeeklyDayInfo(
            LocalDate date,
            List<WeeklySlotInfo> slots) {
    }

    /**
     * 1コマの週間ビュー表示用情報。
     *
     * <p>Phase 2 ではリンク変更情報は含まない（Phase 4 で {@code isChanged} 等を追加予定）。</p>
     */
    public record WeeklySlotInfo(
            Long id,
            @JsonProperty("period_number") Integer periodNumber,
            @JsonProperty("week_pattern") String weekPattern,
            @JsonProperty("subject_name") String subjectName,
            @JsonProperty("course_code") String courseCode,
            @JsonProperty("teacher_name") String teacherName,
            @JsonProperty("room_name") String roomName,
            String color,
            String notes) {
    }
}
