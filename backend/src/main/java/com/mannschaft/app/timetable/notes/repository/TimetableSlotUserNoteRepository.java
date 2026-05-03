package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F03.15 コマ単位個人メモのリポジトリ（Phase 3 で本格使用）。
 */
public interface TimetableSlotUserNoteRepository extends JpaRepository<TimetableSlotUserNoteEntity, Long> {
}
