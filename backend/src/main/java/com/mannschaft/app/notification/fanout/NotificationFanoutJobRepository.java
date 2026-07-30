package com.mannschaft.app.notification.fanout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知 fan-out 耐久ジョブ（P2）用 Repository。
 *
 * <p>本表は {@code organization_id} が NULL の行（SYSTEM スコープ通知）を含むため、
 * {@code AbstractTenantAwareRepository} は適用しない（email_outbox と同じ判断・DDL ヘッダ参照）。</p>
 */
@Repository
public interface NotificationFanoutJobRepository extends JpaRepository<NotificationFanoutJob, UUID> {

    /**
     * 実行対象ジョブを取得するワーカーのメインクエリ（AC-4）。
     *
     * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} により、複数 pod／並行ワーカーが同じ行を取れない
     * （email_outbox の {@code findReadyForSending} と同一パターン）。ロック中の行は<b>飛ばして</b>次の
     * PENDING を返すため、二重処理が構造的に起きない。</p>
     */
    @Query(value = """
            SELECT * FROM notification_fanout_jobs
             WHERE status = 'PENDING'
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationFanoutJob> findReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 冪等キーでジョブを引く（AC-1 の enqueue 冪等ガード補助）。
     * DB のユニーク制約 {@code uk_fanout_idempotency} と対になる。
     */
    Optional<NotificationFanoutJob> findByScopeTypeAndScopeIdAndNotificationTypeAndSourceEventUuid(
            String scopeType, Long scopeId, String notificationType, UUID sourceEventUuid);
}
