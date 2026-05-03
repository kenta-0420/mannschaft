package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F03.15 メモ添付ファイルリポジトリ（Phase 3 で本格使用）。
 */
public interface TimetableSlotUserNoteAttachmentRepository
        extends JpaRepository<TimetableSlotUserNoteAttachmentEntity, Long> {
}
