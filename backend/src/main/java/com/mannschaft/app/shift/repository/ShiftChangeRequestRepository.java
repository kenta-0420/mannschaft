package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * シフト変更依頼リポジトリ。
 */
public interface ShiftChangeRequestRepository extends JpaRepository<ShiftChangeRequestEntity, Long> {

    /**
     * スケジュールの変更依頼一覧を作成日時降順で取得する（ADMIN用）。
     */
    List<ShiftChangeRequestEntity> findAllByScheduleIdOrderByCreatedAtDesc(Long scheduleId);

    /**
     * 特定メンバーのスケジュール内変更依頼一覧を取得する（MEMBER用）。
     */
    List<ShiftChangeRequestEntity> findAllByRequestedByAndScheduleId(Long requestedBy, Long scheduleId);

    /**
     * スケジュールのステータス指定変更依頼一覧を取得する。
     */
    List<ShiftChangeRequestEntity> findAllByScheduleIdAndStatus(Long scheduleId, ChangeRequestStatus status);

    /**
     * 指定ユーザーの当月のオープンコール件数を取得する（月3件上限チェック用）。
     */
    @Query("""
            SELECT COUNT(r) FROM ShiftChangeRequestEntity r
            WHERE r.requestedBy = :userId
              AND r.requestType = :requestType
              AND FUNCTION('YEAR', r.createdAt) = FUNCTION('YEAR', CURRENT_TIMESTAMP)
              AND FUNCTION('MONTH', r.createdAt) = FUNCTION('MONTH', CURRENT_TIMESTAMP)
            """)
    long countByRequestedByAndRequestTypeInCurrentMonth(
            @Param("userId") Long userId,
            @Param("requestType") ChangeRequestType requestType);
}
