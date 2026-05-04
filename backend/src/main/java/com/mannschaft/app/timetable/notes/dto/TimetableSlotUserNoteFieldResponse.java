package com.mannschaft.app.timetable.notes.dto;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;

import java.time.LocalDateTime;

/**
 * F03.15 カスタムメモ項目のレスポンス DTO。
 */
public record TimetableSlotUserNoteFieldResponse(
        Long id,
        String label,
        String placeholder,
        Integer sortOrder,
        Integer maxLength,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TimetableSlotUserNoteFieldResponse from(TimetableSlotUserNoteFieldEntity entity) {
        return new TimetableSlotUserNoteFieldResponse(
                entity.getId(),
                entity.getLabel(),
                entity.getPlaceholder(),
                entity.getSortOrder(),
                entity.getMaxLength(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
