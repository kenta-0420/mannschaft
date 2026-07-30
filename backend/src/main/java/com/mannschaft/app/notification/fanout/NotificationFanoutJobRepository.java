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
     * 実行可能ジョブを返すため、二重処理が構造的に起きない。</p>
     *
     * <p>実行可能とは {@code PENDING}（未処理）または {@code FAILED}（バックオフ後の再試行待ち）で、
     * かつ {@code next_attempt_at <= now} のもの。{@code DEAD_LETTER}／{@code DONE}／{@code RUNNING} は対象外
     * （RUNNING 残骸は {@link NotificationFanoutStuckRecoveryBatch} が PENDING へ戻す）。</p>
     */
    @Query(value = """
            SELECT * FROM notification_fanout_jobs
             WHERE status IN ('PENDING', 'FAILED')
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationFanoutJob> findReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * {@code RUNNING} のまま {@code updated_at} が閾値より古い残骸ジョブを {@code PENDING} へ戻す
     * （ワーカー処理中の pod クラッシュ回収・{@link NotificationFanoutStuckRecoveryBatch}）。
     * カーソルは前進済みのため、再開は「処理済みの直後」から続く（欠落なし）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            UPDATE notification_fanout_jobs
               SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP(3)
             WHERE status = 'RUNNING'
               AND updated_at < :threshold
            """, nativeQuery = true)
    int recoverStuckRunning(@Param("threshold") LocalDateTime threshold);

    /**
     * 冪等キーでジョブを引く（AC-1 の enqueue 冪等ガード補助）。
     * DB のユニーク制約 {@code uk_fanout_idempotency} と対になる。
     */
    Optional<NotificationFanoutJob> findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
            String scopeType, String scopeRef, String notificationType, UUID sourceEventUuid);
}
