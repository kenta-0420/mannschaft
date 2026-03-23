package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * ソースタイプとソースIDで通知件数を取得する。
     */
    long countBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * スコープタイプとスコープIDで通知件数を取得する。
     */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * 全ユーザー横断の未読通知件数を取得する（管理者統計用）。
     */
    long countByIsReadFalse();
}
