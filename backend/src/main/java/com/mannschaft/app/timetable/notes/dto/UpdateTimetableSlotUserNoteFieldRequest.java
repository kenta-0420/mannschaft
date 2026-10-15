package com.mannschaft.app.timetable.notes.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * F03.15 カスタムメモ項目の部分更新リクエスト。
 */
public record UpdateTimetableSlotUserNoteFieldRequest(
        @Size(max = 50) String label,
        @Size(max = 100) String placeholder,
        @PositiveOrZero Integer sortOrder,
        Integer maxLength
) {
}
