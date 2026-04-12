package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F04.9 確認通知リポジトリ。
 */
public interface ConfirmableNotificationRepository
        extends JpaRepository<ConfirmableNotificationEntity, Long> {

    /**
     * スコープ配下の確認通知を作成日時降順で取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知リスト（降順）
     */
    List<ConfirmableNotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            ScopeType scopeType, Long scopeId);

    /**
     * ステータスで確認通知を取得する（バッチジョブ用）。
     *
     * @param status ステータス
     * @return 確認通知リスト
     */
    List<ConfirmableNotificationEntity> findByStatus(ConfirmableNotificationStatus status);

    /**
     * 期限切れとなった ACTIVE 通知を取得する（期限切れバッチジョブ用）。
     *
     * <p>deadline_at が指定日時より前かつ ACTIVE 状態の通知を返す。
     * バッチジョブがこれを取得して {@code expire()} を呼び出す。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知リスト
     */
    @Query("SELECT n FROM ConfirmableNotificationEntity n " +
           "WHERE n.status = 'ACTIVE' AND n.deadlineAt IS NOT NULL AND n.deadlineAt < :now")
    List<ConfirmableNotificationEntity> findExpiredNotifications(@Param("now") LocalDateTime now);
}
