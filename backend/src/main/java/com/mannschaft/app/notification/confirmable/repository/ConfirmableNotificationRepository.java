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

    /**
     * F22.1 市: 発生元（source_type, source_id）に対し指定ステータスの確認通知が存在するか。
     *
     * <p>最終認証通知の重複発火防止に用いる。{@code FULL→OPEN→再FULL} のように札が再度
     * 充足したとき、未確認（{@code ACTIVE}）の {@code MARKET_FINALIZE} 通知が既に存在すれば
     * 再送しない。{@code idx_cn_source(source_type, source_id)} を利用する。</p>
     *
     * @param sourceType 発生元種別（例: {@code MARKET_FINALIZE}）
     * @param sourceId   発生元レコードID（例: {@code recruitment_listings.id}）
     * @param status     ステータス（{@code ACTIVE} = 未確認）
     * @return 存在すれば true
     */
    boolean existsBySourceTypeAndSourceIdAndStatus(
            String sourceType, Long sourceId, ConfirmableNotificationStatus status);
}
