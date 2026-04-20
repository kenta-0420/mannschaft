package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 通知リポジトリ。
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * ユーザーの通知一覧をページング取得する（作成日時降順）。
     */
    Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * ユーザーの未読通知一覧をページング取得する。
     */
    Page<NotificationEntity> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * IDとユーザーIDで通知を取得する。
     */
    Optional<NotificationEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの未読通知件数を取得する。
     */
    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * ユーザーの全通知件数を取得する。
     */
    long countByUserId(Long userId);

    /**
     * ユーザーの未読通知を全て既読にする。
     */
    @Modifying
    @Query("UPDATE NotificationEntity n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") Long userId);

    /**
     * 全ユーザーの未読通知件数を取得する（管理者統計用）。
     */
    long countByIsReadFalse();

    /**
     * ソースタイプとソースIDで通知件数を取得する。
     */
    long countBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * スコープタイプとスコープIDで通知件数を取得する。
     */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * スコープタイプとスコープIDで通知一覧をページング取得する（フレンド通知一覧用）。
     */
    Page<NotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープタイプとスコープIDで未読フィルタ付き通知一覧をページング取得する。
     */
    Page<NotificationEntity> findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Boolean isRead, Pageable pageable);

    /**
     * 指定ユーザー向けに、同一の notification_type / source_type / source_id の通知が
     * 指定時刻以降に既に作成されているか判定する（F04.3 期限リマインダー重複送信防止用）。
     *
     * <p>TODO_OVERDUE の毎朝1回配信を実現するため、当日の 00:00 以降に既に
     * 同一 TODO へ同種通知を送っていればスキップする、といった用途を想定する。</p>
     *
     * @param userId           送信先ユーザー ID
     * @param notificationType 通知種別
     * @param sourceType       ソース種別（例: "TODO"）
     * @param sourceId         ソース ID
     * @param since            判定起点時刻（これ以降に作成された通知を対象）
     * @return 既に送信済みであれば true
     */
    boolean existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
            Long userId, String notificationType, String sourceType, Long sourceId, LocalDateTime since);
}
