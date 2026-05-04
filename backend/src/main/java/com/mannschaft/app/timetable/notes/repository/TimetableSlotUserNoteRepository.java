package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * F03.15 コマ単位個人メモのリポジトリ（Phase 3）。
 */
public interface TimetableSlotUserNoteRepository extends JpaRepository<TimetableSlotUserNoteEntity, Long> {

    /**
     * 指定スロットの常設メモ（target_date IS NULL）を取得する。
     */
    Optional<TimetableSlotUserNoteEntity> findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(
            Long userId, TimetableSlotKind slotKind, Long slotId);

    /**
     * 指定スロットの日付指定メモを取得する。
     */
    Optional<TimetableSlotUserNoteEntity> findByUserIdAndSlotKindAndSlotIdAndTargetDate(
            Long userId, TimetableSlotKind slotKind, Long slotId, LocalDate targetDate);

    /**
     * 指定 ID のメモを所有者検証込みで取得する（IDOR 対策）。
     */
    Optional<TimetableSlotUserNoteEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 指定スロットのメモ一覧（常設＋日付限定）。
     */
    List<TimetableSlotUserNoteEntity> findByUserIdAndSlotKindAndSlotId(
            Long userId, TimetableSlotKind slotKind, Long slotId);

    /**
     * 指定日付のメモ一覧（その日付限定 + 常設の両方）。
     * 「今日のメモ」ダッシュボード用。
     */
    @Query("SELECT n FROM TimetableSlotUserNoteEntity n"
            + " WHERE n.userId = :userId"
            + "   AND (n.targetDate = :date OR n.targetDate IS NULL)"
            + " ORDER BY n.updatedAt DESC")
    List<TimetableSlotUserNoteEntity> findForDate(@Param("userId") Long userId,
                                                   @Param("date") LocalDate date);

    /**
     * 指定期間内に target_date が含まれるメモ一覧。
     * 「今週の準備物」ダッシュボード用。
     */
    @Query("SELECT n FROM TimetableSlotUserNoteEntity n"
            + " WHERE n.userId = :userId"
            + "   AND n.targetDate BETWEEN :from AND :to"
            + " ORDER BY n.targetDate ASC, n.updatedAt DESC")
    List<TimetableSlotUserNoteEntity> findUpcoming(@Param("userId") Long userId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);
}
