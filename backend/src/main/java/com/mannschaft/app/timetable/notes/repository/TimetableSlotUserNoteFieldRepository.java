package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F03.15 ユーザー定義カスタムメモ項目リポジトリ（Phase 3）。
 */
public interface TimetableSlotUserNoteFieldRepository
        extends JpaRepository<TimetableSlotUserNoteFieldEntity, Long> {

    /**
     * 指定ユーザーのカスタム項目を表示順で全件取得する。
     */
    List<TimetableSlotUserNoteFieldEntity> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /**
     * 自分のカスタム項目を 1 件取得する。所有者検証込み。
     */
    Optional<TimetableSlotUserNoteFieldEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 上限到達チェック用（1ユーザーあたり10件）。
     */
    long countByUserId(Long userId);

    /**
     * label 重複チェック用。
     */
    boolean existsByUserIdAndLabel(Long userId, String label);

    /**
     * label 重複チェック用（自身を除外）。
     */
    boolean existsByUserIdAndLabelAndIdNot(Long userId, String label, Long id);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM timetable_slot_user_note_fields WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
