package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * スケジュール出欠リポジトリ。
 */
public interface ScheduleAttendanceRepository extends JpaRepository<ScheduleAttendanceEntity, Long>, JpaSpecificationExecutor<ScheduleAttendanceEntity> {

    /**
     * スケジュールIDとユーザーIDで出欠を取得する。
     */
    Optional<ScheduleAttendanceEntity> findByScheduleIdAndUserId(Long scheduleId, Long userId);

    /**
     * スケジュールIDで出欠一覧を取得する。
     */
    List<ScheduleAttendanceEntity> findByScheduleIdOrderByUserIdAsc(Long scheduleId);

    /**
     * スケジュールID群と単一ユーザーIDで出欠一覧を取得する（一覧APIの自分の出欠バッチ供給用）。
     *
     * <p>{@code ScheduleQueryService#toVisibleScheduleResponses} が一覧内の全スケジュールに対する
     * 閲覧者自身の出欠を N+1 を避けて 1 クエリで取得するために使う。userId は常に呼び出し元の
     * 閲覧者本人（{@code viewerUserId}）に固定し、他ユーザーの出欠を読まない（fail-closed）。
     * scheduleIds が空でも安全に空 List を返す。</p>
     *
     * @param scheduleIds 対象スケジュールID群
     * @param userId      閲覧者ユーザーID
     * @return 該当する出欠一覧
     */
    List<ScheduleAttendanceEntity> findByScheduleIdInAndUserId(Collection<Long> scheduleIds, Long userId);

    /**
     * スケジュールIDとステータスで出欠一覧を取得する。
     */
    List<ScheduleAttendanceEntity> findByScheduleIdAndStatus(Long scheduleId, AttendanceStatus status);

    /**
     * スケジュールIDとステータスで出欠数を取得する。
     */
    long countByScheduleIdAndStatus(Long scheduleId, AttendanceStatus status);

    /**
     * スケジュールIDで出欠レコード総数を取得する（機能55: 出欠募集の冪等性ガード用）。
     *
     * @param scheduleId スケジュールID
     * @return 出欠レコード総数（0 なら未生成）
     */
    long countByScheduleId(Long scheduleId);

    /**
     * スケジュールIDごとのステータス別出欠数を取得する。
     */
    @Query("SELECT a.status, COUNT(a) FROM ScheduleAttendanceEntity a WHERE a.scheduleId = :scheduleId GROUP BY a.status")
    List<Object[]> countByScheduleIdGroupByStatus(@Param("scheduleId") Long scheduleId);

    /**
     * スケジュールIDで出欠レコードを全削除する。
     */
    void deleteByScheduleId(Long scheduleId);

    /**
     * F22.1 第二波: チームスコープで、当該ユーザーが「未回答」の直近イベントを取得する。
     *
     * <p>出欠回答が必要なイベント（{@code attendanceRequired = true}）のうち、当該ユーザーの
     * 出欠行が未回答（{@code status = UNDECIDED} かつ {@code respondedAt IS NULL}）であり、
     * かつイベント開始が {@code now} 以降のものを開始時刻の昇順で返す。N+1 を避けるため
     * {@code schedule_attendances} と {@code schedules} を 1 SQL で JOIN し、スケジュール
     * エンティティを直接返す（{@code @SQLRestriction("deleted_at IS NULL")} で論理削除済は除外）。</p>
     *
     * @param teamId チーム ID
     * @param userId 閲覧ユーザー ID
     * @param now    現在時刻（これ以降に開始するイベントのみ対象）
     * @return 未回答の直近イベント（開始時刻の昇順）
     */
    @Query("""
            SELECT s FROM ScheduleAttendanceEntity a
            JOIN ScheduleEntity s ON s.id = a.scheduleId
            WHERE a.userId = :userId
              AND a.status = com.mannschaft.app.schedule.AttendanceStatus.UNDECIDED
              AND a.respondedAt IS NULL
              AND s.teamId = :teamId
              AND s.attendanceRequired = true
              AND s.startAt >= :now
            ORDER BY s.startAt ASC
            """)
    List<ScheduleEntity> findUnansweredUpcomingForUserInTeam(
            @Param("teamId") Long teamId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);

    /**
     * F22.1 第二波: 組織スコープで、当該ユーザーが「未回答」の直近イベントを取得する。
     * 仕様は {@link #findUnansweredUpcomingForUserInTeam} の組織版。
     *
     * @param organizationId 組織 ID
     * @param userId         閲覧ユーザー ID
     * @param now            現在時刻
     * @return 未回答の直近イベント（開始時刻の昇順）
     */
    @Query("""
            SELECT s FROM ScheduleAttendanceEntity a
            JOIN ScheduleEntity s ON s.id = a.scheduleId
            WHERE a.userId = :userId
              AND a.status = com.mannschaft.app.schedule.AttendanceStatus.UNDECIDED
              AND a.respondedAt IS NULL
              AND s.organizationId = :organizationId
              AND s.attendanceRequired = true
              AND s.startAt >= :now
            ORDER BY s.startAt ASC
            """)
    List<ScheduleEntity> findUnansweredUpcomingForUserInOrganization(
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);
}
