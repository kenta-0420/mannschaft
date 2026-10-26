package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F08.7 Phase 10-β: 失敗イベントリポジトリ。
 *
 * <p>用途:</p>
 * <ul>
 *   <li>{@link #findByStatusInAndLastRetriedAtBeforeOrLastRetriedAtIsNull} —
 *       リトライバッチ用。PENDING/RETRYING かつ最終リトライから {@code threshold} 経過、
 *       または未着手 (last_retried_at IS NULL) の行を抽出</li>
 *   <li>{@link #findByOrganizationId} — 管理 API（一覧）用</li>
 *   <li>{@link #findByOrganizationIdAndStatus} — 管理 API（status 絞り込み）用</li>
 * </ul>
 */
@Repository
public interface ShiftBudgetFailedEventRepository extends JpaRepository<ShiftBudgetFailedEventEntity, Long> {

    /**
     * リトライバッチ用クエリ: {@code statuses} に含まれる行のうち、
     * 最終リトライ時刻が NULL（未着手）または {@code threshold} 以前のものを抽出する。
     *
     * <p>新しいレコードと、リトライから一定時間経過したレコードの両方を拾うために
     * OR で結合している。{@code Pageable} で 1 バッチあたりの処理上限を制御。</p>
     */
    @Query("""
            SELECT e FROM ShiftBudgetFailedEventEntity e
             WHERE e.status IN :statuses
               AND (e.lastRetriedAt IS NULL OR e.lastRetriedAt < :threshold)
             ORDER BY e.id ASC
            """)
    List<ShiftBudgetFailedEventEntity> findRetryablePending(
            @Param("statuses") List<ShiftBudgetFailedEventStatus> statuses,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    /** 管理 API: 組織配下の全失敗イベントを新しい順に取得。 */
    @Query("""
            SELECT e FROM ShiftBudgetFailedEventEntity e
             WHERE e.organizationId = :orgId
             ORDER BY e.id DESC
            """)
    List<ShiftBudgetFailedEventEntity> findByOrganizationId(
            @Param("orgId") Long organizationId,
            Pageable pageable);

    /** 管理 API: 組織配下を status で絞り込み、新しい順に取得。 */
    @Query("""
            SELECT e FROM ShiftBudgetFailedEventEntity e
             WHERE e.organizationId = :orgId
               AND e.status = :status
             ORDER BY e.id DESC
            """)
    List<ShiftBudgetFailedEventEntity> findByOrganizationIdAndStatus(
            @Param("orgId") Long organizationId,
            @Param("status") ShiftBudgetFailedEventStatus status,
            Pageable pageable);
}
