package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * F03.15 個人時間割ユーザー設定 PUT リクエスト（UPSERT）。
 *
 * <p>未指定（null）のフィールドは現状維持する。{@code visible_default_fields} は
 * リスト全体置換、空配列の場合はデフォルト4項目に戻す。</p>
 */
public record UpdatePersonalTimetableSettingsRequest(
        @JsonProperty("auto_reflect_class_changes_to_calendar") Boolean autoReflectClassChangesToCalendar,
        @JsonProperty("notify_team_slot_note_updates") Boolean notifyTeamSlotNoteUpdates,
        @JsonProperty("default_period_template") String defaultPeriodTemplate,
        @JsonProperty("visible_default_fields") List<String> visibleDefaultFields
) {
}
