package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * シフトスケジュールリポジトリ。
 */
public interface ShiftScheduleRepository extends JpaRepository<ShiftScheduleEntity, Long> {

    /**
     * チームのシフトスケジュール一覧を開始日降順で取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdOrderByStartDateDesc(Long teamId);

    /**
     * チームとステータスでシフトスケジュールを取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdAndStatus(Long teamId, ShiftScheduleStatus status);

    /**
     * チームIDとIDでシフトスケジュールを取得する。
     */
    Optional<ShiftScheduleEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームの期間指定でシフトスケジュールを取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdAndStartDateBetweenOrderByStartDateDesc(
            Long teamId, LocalDate from, LocalDate to);

    /**
     * 特定ステータスのスケジュール一覧を取得する（自動遷移用）。
     */
    List<ShiftScheduleEntity> findByStatus(ShiftScheduleStatus status);

    /**
     * 横断検索（グローバル検索）用のキーワード検索。閲覧者の所属チームに限定する。
     *
     * <p>シフト表は TEAM スコープ専用（{@code teamId} が not-null、組織／個人スコープを持たない）のため、
     * 閲覧者が所属するチームの ID 集合で絞り込む。</p>
     *
     * <p><b>未公開シフト表の遮断（CMP-260826-2127 / AC-6）</b>: 閲覧者が管理者であるチームと
     * 一般メンバーであるチームを 2 集合に分けて受け取り、後者については
     * {@link ShiftScheduleEntity#NOT_HIDDEN_JPQL} を満たすものだけをヒットさせる。
     * <b>絞りは必ず SQL 述語側で行う</b> — 取得後に Java でフィルタすると、上限件数
     *（{@code SEARCH_LIMIT = 10}）を取ってから削るため結果がさらに痩せる。
     * 検索対象は {@code title} だけでなく {@code note} も含むため、
     * note にしか無い語での総当りによる推定もこの述語で封じられる。</p>
     *
     * <p>呼び出し側は各 ID 集合が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword       検索キーワード
     * @param adminTeamIds  閲覧者が ADMIN 相当であるチーム ID 集合（全ステータスがヒットする）
     * @param memberTeamIds 閲覧者が一般メンバーであるチーム ID 集合（未公開はヒットしない）
     * @param pageable      取得件数
     * @return 所属チーム内の検索結果
     */
    @org.springframework.data.jpa.repository.Query("SELECT s FROM ShiftScheduleEntity s "
            + "WHERE (s.title LIKE %:keyword% OR s.note LIKE %:keyword%) "
            + "AND s.deletedAt IS NULL "
            + "AND (s.teamId IN :adminTeamIds "
            + "     OR (s.teamId IN :memberTeamIds AND " + ShiftScheduleEntity.NOT_HIDDEN_JPQL + "))")
    List<ShiftScheduleEntity> searchByKeyword(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("adminTeamIds") java.util.Collection<Long> adminTeamIds,
            @org.springframework.data.repository.query.Param("memberTeamIds") java.util.Collection<Long> memberTeamIds,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 48h リマインド対象: COLLECTING・48hフラグ未送信・期限が now〜now+48h 以内。
     */
    @Query("""
            SELECT s FROM ShiftScheduleEntity s
            WHERE s.status = 'COLLECTING'
              AND s.isReminderSent48h = FALSE
              AND s.requestDeadline IS NOT NULL
              AND s.requestDeadline BETWEEN :now AND :threshold48h
              AND s.deletedAt IS NULL
            """)
    List<ShiftScheduleEntity> findFor48hReminder(
            @Param("now") LocalDateTime now,
            @Param("threshold48h") LocalDateTime threshold48h);

    /**
     * 24h リマインド対象: COLLECTING・24hフラグ未送信・期限が now〜now+24h 以内。
     */
    @Query("""
            SELECT s FROM ShiftScheduleEntity s
            WHERE s.status = 'COLLECTING'
              AND s.isReminderSent = FALSE
              AND s.requestDeadline IS NOT NULL
              AND s.requestDeadline BETWEEN :now AND :threshold24h
              AND s.deletedAt IS NULL
            """)
    List<ShiftScheduleEntity> findFor24hReminder(
            @Param("now") LocalDateTime now,
            @Param("threshold24h") LocalDateTime threshold24h);

    /**
     * 自動アーカイブ対象: PUBLISHED かつ endDate が cutoffDate より前。
     */
    @Query("""
            SELECT s FROM ShiftScheduleEntity s
            WHERE s.status = 'PUBLISHED'
              AND s.endDate < :cutoffDate
              AND s.deletedAt IS NULL
            """)
    List<ShiftScheduleEntity> findPublishedExpiredBefore(
            @Param("cutoffDate") LocalDate cutoffDate,
            Pageable pageable);

    /**
     * ARCHIVED かつ updatedAt が cutoff より前のスケジュール ID を返す（希望物理削除用）。
     */
    @Query("""
            SELECT s.id FROM ShiftScheduleEntity s
            WHERE s.status = 'ARCHIVED'
              AND s.updatedAt < :cutoff
              AND s.deletedAt IS NULL
            """)
    List<Long> findArchivedScheduleIdsOlderThan(
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);
}
