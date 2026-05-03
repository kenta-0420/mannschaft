package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F03.15 ユーザー定義カスタムメモ項目リポジトリ（Phase 3 で本格使用）。
 */
public interface TimetableSlotUserNoteFieldRepository
        extends JpaRepository<TimetableSlotUserNoteFieldEntity, Long> {
}
