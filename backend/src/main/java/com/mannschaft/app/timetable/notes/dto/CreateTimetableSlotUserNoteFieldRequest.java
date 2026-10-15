package com.mannschaft.app.timetable.notes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * F03.15 カスタムメモ項目の作成リクエスト。
 */
public record CreateTimetableSlotUserNoteFieldRequest(
        @NotBlank @Size(max = 50) String label,
        @Size(max = 100) String placeholder,
        @PositiveOrZero Integer sortOrder,
        Integer maxLength
) {
}
