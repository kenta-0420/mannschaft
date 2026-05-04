package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * F03.15 ダッシュボード「今日の時間割」レスポンス。
 *
 * <p>チーム時間割と個人時間割を時間順でマージして返す。Phase 3 ではリンク連動による
 * 臨時変更反映（is_changed / change）は未実装のため常に false / null。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardTimetableTodayResponse(
        LocalDate date,
        @JsonProperty("week_pattern") String weekPattern,
        List<TimetableTodayItem> items
) {
    /**
     * 1コマ分の情報。{@code source_kind} で TEAM / PERSONAL を識別する。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimetableTodayItem(
            @JsonProperty("source_kind") String sourceKind,
            @JsonProperty("source_team_id") Long sourceTeamId,
            @JsonProperty("source_team_name") String sourceTeamName,
            @JsonProperty("personal_timetable_id") Long personalTimetableId,
            @JsonProperty("timetable_id") Long timetableId,
            @JsonProperty("slot_id") Long slotId,
            @JsonProperty("period_label") String periodLabel,
            @JsonProperty("period_number") Integer periodNumber,
            @JsonProperty("start_time") LocalTime startTime,
            @JsonProperty("end_time") LocalTime endTime,
            @JsonProperty("subject_name") String subjectName,
            @JsonProperty("course_code") String courseCode,
            @JsonProperty("teacher_name") String teacherName,
            @JsonProperty("room_name") String roomName,
            BigDecimal credits,
            String color,
            @JsonProperty("linked_team_id") Long linkedTeamId,
            @JsonProperty("is_changed") Boolean isChanged,
            Object change,
            @JsonProperty("link_revoked") Boolean linkRevoked,
            @JsonProperty("user_note_id") Long userNoteId,
            @JsonProperty("has_attachments") Boolean hasAttachments
    ) {
    }
}
