package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.visibility.EventVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * イベントリポジトリ。
 */
public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /**
     * スコープ別イベント一覧をページング取得する。
     */
    Page<EventEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            EventScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別・ステータス指定でイベント一覧をページング取得する。
     */
    Page<EventEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            EventScopeType scopeType, Long scopeId, EventStatus status, Pageable pageable);

    /**
     * 公開範囲・ステータス指定でイベント一覧をページング取得する。
     */
    Page<EventEntity> findByVisibilityAndStatusOrderByCreatedAtDesc(
            EventVisibility visibility, EventStatus status, Pageable pageable);

    /**
     * スラグでイベントを取得する。
     */
    Optional<EventEntity> findBySlug(String slug);

    /**
     * スラグの存在を確認する。
     */
    boolean existsBySlug(String slug);

    /**
     * スコープ別のイベント件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(EventScopeType scopeType, Long scopeId, EventStatus status);

    @Query("SELECT e FROM EventEntity e WHERE e.subtitle LIKE %:keyword% OR e.summary LIKE %:keyword% OR e.venueName LIKE %:keyword%")
    List<EventEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 現在進行中（schedules.startAt が :cutoff 以前かつ schedules.endAt が :now 以降）の
     * イベントID一覧を取得する。
     *
     * <p>F03.12 Phase4 バッチが「開始からN分以上経過した進行中イベント」を特定するために使用する。
     * EventEntity.scheduleId → ScheduleEntity の JOIN で開始・終了時刻を判定する。</p>
     *
     * @param now    現在日時（endsAt の比較基準: この時刻より endAt が後であれば進行中）
     * @param cutoff カットオフ日時（startAt がこの時刻以前であればN分経過済み）
     * @return 条件を満たすイベントIDリスト
     */
    @Query("""
            SELECT e.id
            FROM EventEntity e
            JOIN ScheduleEntity s ON s.id = e.scheduleId
            WHERE s.startAt <= :cutoff
              AND (s.endAt IS NULL OR s.endAt > :now)
            """)
    List<Long> findActiveEventIdsStartedBefore(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * 解散通知リマインド対象のイベントを取得する（F03.12 §16）。
     *
     * <p>以下の条件をすべて満たすイベントを返す:</p>
     * <ul>
     *   <li>dismissal_notification_sent_at が NULL（未解散）</li>
     *   <li>schedules.end_at が now より前（終了時刻を過ぎている）</li>
     *   <li>organizer_reminder_sent_count が maxReminderCount 未満（リマインド上限未到達）</li>
     *   <li>schedules.end_at が cutoff より前（endAt から minElapsedMinutes 分以上経過）</li>
     * </ul>
     *
     * @param now              現在日時
     * @param cutoff           カットオフ日時（endAt がこれより前であれば経過済み）
     * @param maxReminderCount リマインド上限回数（この値以上は対象外）
     * @return 条件を満たすイベントエンティティリスト
     */
    @Query("""
            SELECT e
            FROM EventEntity e
            JOIN ScheduleEntity s ON s.id = e.scheduleId
            WHERE e.dismissalNotificationSentAt IS NULL
              AND s.endAt IS NOT NULL
              AND s.endAt < :cutoff
              AND e.organizerReminderSentCount < :maxReminderCount
            """)
    List<EventEntity> findDismissalReminderTargets(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("maxReminderCount") int maxReminderCount);

    /**
     * イベントIDとチームIDでイベントを取得する（スコープ検証付き）。
     *
     * <p>チームスコープのイベントのみを対象とする。スコープ不一致は空を返す。</p>
     *
     * @param eventId イベントID
     * @param scopeId チームID（TEAM スコープの scopeId）
     * @return イベント（存在しない or スコープ不一致の場合は empty）
     */
    @Query("""
            SELECT e
            FROM EventEntity e
            WHERE e.id = :eventId
              AND e.scopeType = 'TEAM'
              AND e.scopeId = :scopeId
            """)
    Optional<EventEntity> findByIdAndTeamScopeId(
            @Param("eventId") Long eventId,
            @Param("scopeId") Long scopeId);

    /**
     * ログインユーザーが主催しており、終了予定時刻を過ぎたが解散通知が未送信の
     * チームスコープイベント一覧を取得する（F03.12 Phase11 / §16 Widget 連携）。
     *
     * <p>条件:</p>
     * <ul>
     *   <li>{@code e.created_by = :userId}（主催者）</li>
     *   <li>{@code e.dismissal_notification_sent_at IS NULL}（未解散）</li>
     *   <li>{@code e.scope_type = 'TEAM'}（チームスコープのみ）</li>
     *   <li>{@code s.end_at IS NOT NULL AND s.end_at < :cutoff}（終了時刻を過ぎている）</li>
     * </ul>
     *
     * <p>結果は {@link DismissalReminderTargetProjection} 型のフラット投影で返し、
     * Service 側で経過分数の算出と DTO 組み立てを行う。
     * チーム名はリストカードのラベル用にあらかじめ JOIN で取得する。</p>
     *
     * @param userId ログインユーザーID（主催者）
     * @param cutoff 「終了時刻を過ぎている」の判定基準（通常は現在時刻）
     * @return 投影結果のリスト。endAt 昇順（古いものから先に表示）。
     */
    @Query("""
            SELECT e.id AS eventId,
                   e.subtitle AS subtitle,
                   e.slug AS slug,
                   t.id AS teamId,
                   t.name AS teamName,
                   s.endAt AS endAt,
                   e.organizerReminderSentCount AS reminderCount
            FROM EventEntity e
            JOIN ScheduleEntity s ON s.id = e.scheduleId
            JOIN com.mannschaft.app.team.entity.TeamEntity t ON t.id = e.scopeId
            WHERE e.createdBy = :userId
              AND e.dismissalNotificationSentAt IS NULL
              AND e.scopeType = 'TEAM'
              AND s.endAt IS NOT NULL
              AND s.endAt < :cutoff
            ORDER BY s.endAt ASC
            """)
    List<DismissalReminderTargetProjection> findMyOrganizingUndismissedExpiredEvents(
            @Param("userId") Long userId,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * F00 共通可視性基盤 — {@link EventVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §6.3.2 工程 6。
     *
     * <p>{@code EventEntity} の {@code @SQLRestriction("deleted_at IS NULL")} により
     * 論理削除済の行は自動的に除外されるため、明示の WHERE 句は不要。
     * 本メソッドは Resolver の {@code AbstractContentVisibilityResolver#loadProjections} から
     * のみ呼ばれ、戻り値の順序は保証しない。
     *
     * @param ids 取得対象 event_id 集合（空の場合は空 List を返す）
     * @return 実存する events の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.event.visibility.EventVisibilityProjection(
                e.id,
                CASE
                    WHEN e.scopeType = com.mannschaft.app.event.EventScopeType.TEAM THEN 'TEAM'
                    WHEN e.scopeType = com.mannschaft.app.event.EventScopeType.ORGANIZATION THEN 'ORGANIZATION'
                    ELSE NULL
                END,
                e.scopeId,
                e.createdBy,
                e.status,
                e.visibility)
            FROM EventEntity e
            WHERE e.id IN :ids AND e.deletedAt IS NULL
            """)
    List<EventVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);

    /**
     * {@link #findMyOrganizingUndismissedExpiredEvents} の投影インターフェース。
     *
     * <p>Spring Data JPA の Interface-based Projection。
     * {@code subtitle} が NULL のときの fallback (slug) は Service 層で組み立てる。</p>
     */
    interface DismissalReminderTargetProjection {
        Long getEventId();

        String getSubtitle();

        String getSlug();

        Long getTeamId();

        String getTeamName();

        LocalDateTime getEndAt();

        Byte getReminderCount();
    }
}
