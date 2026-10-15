package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;

import java.time.LocalDateTime;

/**
 * F03.15 添付ファイルのレスポンス DTO。
 */
public record AttachmentResponse(
        Long id,
        @JsonProperty("note_id") Long noteId,
        @JsonProperty("original_filename") String originalFilename,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
    public static AttachmentResponse from(TimetableSlotUserNoteAttachmentEntity entity) {
        return new AttachmentResponse(
                entity.getId(),
                entity.getNoteId(),
                entity.getOriginalFilename(),
                entity.getMimeType(),
                entity.getSizeBytes(),
                entity.getCreatedAt()
        );
    }
}
