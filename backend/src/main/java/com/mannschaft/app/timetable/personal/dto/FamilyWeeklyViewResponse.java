package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * F03.15 Phase 5 家族閲覧用 週間ビューレスポンス。
 *
 * <p>本人向け {@link PersonalWeeklyViewResponse} と異なり、コマ単位で以下を
 * <strong>意図的に除外</strong>する（設計書 §4 / §6.1 参照）:</p>
 * <ul>
 *   <li>{@code notes}（コマ自体の備考。本人向け常設情報なので家族には見せない）</li>
 *   <li>{@code linked_team_id} / {@code linked_timetable_id} / {@code linked_slot_id}
 *       （内部組織関係の漏洩防止。家族側がリンク先チーム MEMBER でないケースを想定）</li>
 *   <li>{@code auto_sync_changes}（リンク機能の存在自体を露出させない）</li>
 *   <li>{@code user_note_id} / {@code has_attachments}（個人メモは家族からは絶対に閲覧不可）</li>
 *   <li>{@code is_changed} / {@code change}（休講・補講等の臨時変更情報。本人スケジュール特有のため）</li>
 * </ul>
 *
 * <p>表示可: id, period_number, week_pattern, subject_name, course_code, teacher_name,
 * room_name, credits, color。</p>
 */
public record FamilyWeeklyViewResponse(
        @JsonProperty("personal_timetable_id") Long personalTimetableId,
        @JsonProperty("personal_timetable_name") String personalTimetableName,
        @JsonProperty("week_start") LocalDate weekStart,
        @JsonProperty("week_end") LocalDate weekEnd,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("current_week_pattern") String currentWeekPattern,
        List<PersonalTimetablePeriodResponse> periods,
        /** キー: MON/TUE/WED/THU/FRI/SAT/SUN */
        Map<String, FamilyDayInfo> days) {

    /** 曜日ごとの日次情報。 */
    public record FamilyDayInfo(
            LocalDate date,
            List<FamilySlotInfo> slots) {
    }

    /** 1コマの家族閲覧用情報（リンク・メモ系を除外）。 */
    public record FamilySlotInfo(
            Long id,
            @JsonProperty("period_number") Integer periodNumber,
            @JsonProperty("week_pattern") String weekPattern,
            @JsonProperty("subject_name") String subjectName,
            @JsonProperty("course_code") String courseCode,
            @JsonProperty("teacher_name") String teacherName,
            @JsonProperty("room_name") String roomName,
            java.math.BigDecimal credits,
            String color) {
    }
}
