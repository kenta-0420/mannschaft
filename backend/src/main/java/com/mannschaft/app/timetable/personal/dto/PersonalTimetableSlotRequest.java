package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * F03.15 Phase 2 個人時間割のコマリクエスト要素。
 *
 * <p>{@code linkedTeamId} / {@code linkedTimetableId} / {@code linkedSlotId} は受け取った場合
 * Service 層で 400 エラーを返す（Phase 4 で本実装予定）。</p>
 */
public record PersonalTimetableSlotRequest(
        @JsonProperty("day_of_week")
        @NotBlank
        @Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN")
        String dayOfWeek,

        @JsonProperty("period_number")
        @NotNull
        @Min(1)
        @Max(15)
        Integer periodNumber,

        @JsonProperty("week_pattern")
        @Pattern(regexp = "EVERY|A|B")
        String weekPattern,

        @JsonProperty("subject_name")
        @NotBlank
        @Size(max = 200)
        String subjectName,

        @JsonProperty("course_code")
        @Size(max = 50)
        String courseCode,

        @JsonProperty("teacher_name")
        @Size(max = 100)
        String teacherName,

        @JsonProperty("room_name")
        @Size(max = 200)
        String roomName,

        BigDecimal credits,

        @Size(max = 7)
        String color,

        // ---- Phase 4 で本実装。Phase 2 では値が入っていると 400 拒否 ----
        @JsonProperty("linked_team_id")
        Long linkedTeamId,

        @JsonProperty("linked_timetable_id")
        Long linkedTimetableId,

        @JsonProperty("linked_slot_id")
        Long linkedSlotId,

        @JsonProperty("auto_sync_changes")
        Boolean autoSyncChanges,

        @Size(max = 300)
        String notes) {
}
