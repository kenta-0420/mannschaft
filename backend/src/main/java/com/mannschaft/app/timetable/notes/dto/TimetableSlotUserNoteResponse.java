package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.15 個人メモのレスポンス DTO。
 *
 * <p>本人のみ閲覧可。家族閲覧 API ではこの DTO を返さない（DTO レイヤーで強制除外）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimetableSlotUserNoteResponse(
        Long id,
        TimetableSlotKind slotKind,
        Long slotId,
        String preparation,
        String review,
        String itemsToBring,
        String freeMemo,
        List<CustomFieldValue> customFields,
        LocalDate targetDate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * カスタムフィールドの値1件。
     *
     * @param fieldId    対応するカスタム項目 ID
     * @param value      入力された値
     * @param isOrphaned カスタム項目が削除済みで参照不能な場合 true
     */
    public record CustomFieldValue(Long fieldId, String value, Boolean isOrphaned) {
    }

    public static TimetableSlotUserNoteResponse from(TimetableSlotUserNoteEntity entity,
                                                     List<CustomFieldValue> customFields) {
        return new TimetableSlotUserNoteResponse(
                entity.getId(),
                entity.getSlotKind(),
                entity.getSlotId(),
                entity.getPreparation(),
                entity.getReview(),
                entity.getItemsToBring(),
                entity.getFreeMemo(),
                customFields,
                entity.getTargetDate(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
