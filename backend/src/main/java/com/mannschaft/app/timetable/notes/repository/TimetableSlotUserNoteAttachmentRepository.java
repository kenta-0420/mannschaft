package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F03.15 メモ添付ファイルリポジトリ（Phase 3）。
 */
public interface TimetableSlotUserNoteAttachmentRepository
        extends JpaRepository<TimetableSlotUserNoteAttachmentEntity, Long> {

    /**
     * 指定 ID の添付を所有者検証込みで取得する。
     */
    Optional<TimetableSlotUserNoteAttachmentEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 1 メモあたりの未削除添付件数（上限5件のチェック用）。
     */
    long countByNoteIdAndDeletedAtIsNull(Long noteId);

    /**
     * メモ ID 群のうち未削除添付を持つものの ID を返す（ダッシュボード N+1 解消用）。
     */
    @Query("SELECT DISTINCT a.noteId FROM TimetableSlotUserNoteAttachmentEntity a"
            + " WHERE a.noteId IN :noteIds AND a.deletedAt IS NULL")
    List<Long> findNoteIdsHavingAttachments(@Param("noteIds") java.util.Collection<Long> noteIds);

    /**
     * 指定メモの未削除添付一覧。
     */
    List<TimetableSlotUserNoteAttachmentEntity> findByNoteIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long noteId);

    /**
     * R2 オブジェクトキー検索（confirm の冪等化用）。
     */
    Optional<TimetableSlotUserNoteAttachmentEntity> findByR2ObjectKey(String r2ObjectKey);

    /**
     * 1 ユーザーあたりの未削除累計サイズ（クォータ判定用、単位: バイト）。
     */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM TimetableSlotUserNoteAttachmentEntity a"
            + " WHERE a.userId = :userId AND a.deletedAt IS NULL")
    long sumSizeBytesByUser(@Param("userId") Long userId);
}
