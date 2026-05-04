package com.mannschaft.app.timetable.notes.repository;

import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
