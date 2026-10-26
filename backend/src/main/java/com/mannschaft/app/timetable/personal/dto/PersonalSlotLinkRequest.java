package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * F03.15 Phase 4: 個人コマのチームリンク登録/更新リクエスト。
 */
public record PersonalSlotLinkRequest(
        @NotNull
        @JsonProperty("linked_team_id")
        Long linkedTeamId,

        @NotNull
        @JsonProperty("linked_timetable_id")
        Long linkedTimetableId,

        @JsonProperty("linked_slot_id")
        Long linkedSlotId,

        @JsonProperty("auto_sync_changes")
        Boolean autoSyncChanges
) {}
