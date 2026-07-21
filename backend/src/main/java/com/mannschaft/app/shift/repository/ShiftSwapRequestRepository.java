package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * シフト交代リクエストリポジトリ。
 */
public interface ShiftSwapRequestRepository extends JpaRepository<ShiftSwapRequestEntity, Long> {

    /**
     * シフト枠の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findBySlotId(Long slotId);

    /**
     * リクエスターの交代リクエスト一覧をステータスでフィルタして取得する。
     */
    List<ShiftSwapRequestEntity> findByRequesterIdAndStatus(Long requesterId, SwapRequestStatus status);

    /**
     * リクエスターの全交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);

    /**
     * 承諾者の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByAccepterIdOrderByCreatedAtDesc(Long accepterId);

    /**
     * 特定ステータスの交代リクエスト一覧を取得する（管理者用）。
     */
    List<ShiftSwapRequestEntity> findByStatusOrderByCreatedAtAsc(SwapRequestStatus status);

    /**
     * オープンコール中の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByIsOpenCallTrueAndStatus(SwapRequestStatus status);

    /**
     * 48h 経過した PENDING 交代申請をバッチ取得する（自動期限切れキャンセル用）。
     */
    @Query("""
            SELECT r FROM ShiftSwapRequestEntity r
            WHERE r.status = 'PENDING'
              AND r.createdAt < :cutoff
            ORDER BY r.createdAt ASC
            """)
    List<ShiftSwapRequestEntity> findExpiredPendingBefore(
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    // ─────────────────────────────────────────────
    // F10.1.1 P1: 管理者向け承認待ち集約（team 単位・read-only）
    // slotId → ShiftSlotEntity.scheduleId → ShiftScheduleEntity.teamId を JOIN し、
    // WHERE に team_id を必須で含める（IDOR/テナント越境防止）。既存は status 単位のみのため新設。
    // ─────────────────────────────────────────────

    /**
     * 指定チームの指定ステータスのシフト交代申請の件数を返す。
     * slotId → ShiftSlotEntity → ShiftScheduleEntity.teamId を JOIN して team で絞り込む。
     */
    @Query("""
            SELECT COUNT(r) FROM ShiftSwapRequestEntity r,
                   com.mannschaft.app.shift.entity.ShiftSlotEntity sl,
                   com.mannschaft.app.shift.entity.ShiftScheduleEntity s
            WHERE r.slotId = sl.id
              AND sl.scheduleId = s.id
              AND s.teamId = :teamId
              AND r.status = :status
            """)
    long countPendingByTeam(@Param("teamId") Long teamId,
                            @Param("status") SwapRequestStatus status);

    /**
     * 指定チームの指定ステータスのシフト交代申請を作成日時降順でプレビュー取得する。
     * slotId → ShiftSlotEntity → ShiftScheduleEntity.teamId を JOIN して team で絞り込む。
     */
    @Query("""
            SELECT r FROM ShiftSwapRequestEntity r,
                   com.mannschaft.app.shift.entity.ShiftSlotEntity sl,
                   com.mannschaft.app.shift.entity.ShiftScheduleEntity s
            WHERE r.slotId = sl.id
              AND sl.scheduleId = s.id
              AND s.teamId = :teamId
              AND r.status = :status
            ORDER BY r.createdAt DESC
            """)
    List<ShiftSwapRequestEntity> findPendingByTeam(@Param("teamId") Long teamId,
                                                   @Param("status") SwapRequestStatus status,
                                                   Pageable pageable);

    // ─────────────────────────────────────────────
    // 認可根治 Wave6: 管理者向け一覧の team スコープ絞り込み
    // 既存の countPendingByTeam / findPendingByTeam と同じ JOIN 経路
    //（slotId → ShiftSlotEntity.scheduleId → ShiftScheduleEntity.teamId）を踏襲する。
    // ─────────────────────────────────────────────

    /**
     * 指定チームの交代リクエストを作成日時昇順で全件取得する。
     *
     * @param teamId チームID
     * @return 当該チームに属する交代リクエスト一覧
     */
    @Query("""
            SELECT r FROM ShiftSwapRequestEntity r,
                   com.mannschaft.app.shift.entity.ShiftSlotEntity sl,
                   com.mannschaft.app.shift.entity.ShiftScheduleEntity s
            WHERE r.slotId = sl.id
              AND sl.scheduleId = s.id
              AND s.teamId = :teamId
            ORDER BY r.createdAt ASC
            """)
    List<ShiftSwapRequestEntity> findByTeamIdOrderByCreatedAtAsc(@Param("teamId") Long teamId);

    /**
     * 指定チームの指定ステータスの交代リクエストを作成日時昇順で取得する。
     *
     * @param teamId チームID
     * @param status ステータス
     * @return 当該チーム・当該ステータスの交代リクエスト一覧
     */
    @Query("""
            SELECT r FROM ShiftSwapRequestEntity r,
                   com.mannschaft.app.shift.entity.ShiftSlotEntity sl,
                   com.mannschaft.app.shift.entity.ShiftScheduleEntity s
            WHERE r.slotId = sl.id
              AND sl.scheduleId = s.id
              AND s.teamId = :teamId
              AND r.status = :status
            ORDER BY r.createdAt ASC
            """)
    List<ShiftSwapRequestEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(@Param("teamId") Long teamId,
                                                                          @Param("status") SwapRequestStatus status);
}
