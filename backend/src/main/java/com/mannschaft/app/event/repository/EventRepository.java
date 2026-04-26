package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
}
