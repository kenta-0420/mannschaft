package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

/**
 * F03.15 個人メモ アップサートリクエスト。
 *
 * <p>(slot_kind, slot_id, target_date) で一意。target_date が NULL の場合は常設メモ。</p>
 */
public record UpsertTimetableSlotUserNoteRequest(
        @NotNull @JsonProperty("slot_kind") TimetableSlotKind slotKind,
        @NotNull @Positive @JsonProperty("slot_id") Long slotId,
        @JsonProperty("target_date") LocalDate targetDate,
        String preparation,
        String review,
        @JsonProperty("items_to_bring") String itemsToBring,
        @JsonProperty("free_memo") String freeMemo,
        @JsonProperty("custom_fields") List<CustomFieldInput> customFields
) {
    /**
     * カスタムフィールドへの入力値。
     */
    public record CustomFieldInput(
            @NotNull @JsonProperty("field_id") Long fieldId,
            String value
    ) {
    }
}
