package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.15 個人時間割ユーザー設定レスポンス。
 */
public record PersonalTimetableSettingsResponse(
        @JsonProperty("auto_reflect_class_changes_to_calendar") Boolean autoReflectClassChangesToCalendar,
        @JsonProperty("notify_team_slot_note_updates") Boolean notifyTeamSlotNoteUpdates,
        @JsonProperty("default_period_template") String defaultPeriodTemplate,
        @JsonProperty("visible_default_fields") List<String> visibleDefaultFields,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static PersonalTimetableSettingsResponse from(PersonalTimetableSettingsEntity entity,
                                                         List<String> visibleDefaultFields) {
        return new PersonalTimetableSettingsResponse(
                entity.getAutoReflectClassChangesToCalendar(),
                entity.getNotifyTeamSlotNoteUpdates(),
                entity.getDefaultPeriodTemplate().name(),
                visibleDefaultFields,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
