package com.mannschaft.app.notification.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 通知保持（アーカイブ移送）バッチ。
 *
 * <p>保持期間を超えた {@code notifications} 行を物理削除せず
 * {@code notifications_archive} へ移送してから本体を削除し、{@code notifications}
 * テーブルの肥大化を防ぐ。移送により per-row 状態（is_read / read_at / priority 等）を
 * 失わずに履歴を残せる。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎日 AM 4:00 JST（他アーカイブバッチと時間帯をずらす）</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>移送対象（2軸）</h2>
 * <ul>
 *   <li>既読: {@code is_read = TRUE  AND created_at < NOW() - 90日}（{@link #RETENTION_DAYS}）</li>
 *   <li>未読エイジング: {@code is_read = FALSE AND created_at < NOW() - 365日}（{@link #UNREAD_AGING_DAYS}）</li>
 * </ul>
 *
 * <h2>処理フロー（チャンク単位で独立コミット＝at-least-once・欠落なし）</h2>
 * <ol>
 *   <li>閾値より古い行を {@code INSERT IGNORE INTO ... SELECT} で archive へ移送</li>
 *   <li>archive に入った id のみ本体から DELETE（存在確認付き安全弁）</li>
 *   <li>1バッチあたり最大 {@link #BATCH_SIZE} 件（メモリ枯渇防止）</li>
 * </ol>
 *
 * <p><b>Tx 境界の根治:</b> 同一クラス内 {@code @Transactional} メソッドを
 * {@code this} 経由で自己呼び出すと Spring の AOP プロキシを通らず Tx が効かない。
 * そのため各チャンクの INSERT/DELETE は {@link TransactionTemplate} で明示的に
 * 独立コミットさせ、全体を単一の巨大トランザクションにしない。</p>
 */
@Slf4j
@Service
public class NotificationCleanupBatchService {

    /** 既読の保持日数（超過分を移送）。 */
    private static final int RETENTION_DAYS = 90;
    /** 未読エイジングの保持日数（超過した未読も移送し青天井を封鎖）。 */
    private static final int UNREAD_AGING_DAYS = 365;
    private static final int BATCH_SIZE = 10_000;

    /** 移送対象 WHERE（既読90日超 OR 未読365日超）。バインドは (readThreshold, unreadThreshold)。 */
    private static final String MOVE_PREDICATE =
            "((is_read = TRUE AND created_at < ?) OR (is_read = FALSE AND created_at < ?))";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public NotificationCleanupBatchService(JdbcTemplate jdbcTemplate,
                                           PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BatchEndpoint(name = "notification-cleanup",
            description = "保持期間超過の通知（既読90日/未読365日）を毎日 04:00 に notifications_archive へ移送する")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "notificationCleanupBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void cleanupOldReadNotifications() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime readThreshold = now.minusDays(RETENTION_DAYS);
        LocalDateTime unreadThreshold = now.minusDays(UNREAD_AGING_DAYS);
        log.info("[NotificationRetentionBatch] 移送開始: 既読基準={}, 未読基準={}", readThreshold, unreadThreshold);

        long totalArchived = 0;
        long totalDeleted = 0;

        try {
            while (true) {
                int archived = insertIntoArchive(readThreshold, unreadThreshold);
                if (archived == 0) break;

                int deleted = deleteArchived(readThreshold, unreadThreshold, archived);
                totalArchived += archived;
                totalDeleted += deleted;

                log.info("[NotificationRetentionBatch] バッチ完了: 移送={}件, 削除={}件（累計: 移送={}, 削除={}）",
                        archived, deleted, totalArchived, totalDeleted);

                if (archived < BATCH_SIZE) break;
            }

            log.info("[NotificationRetentionBatch] 完了: 総移送={}件, 総削除={}件", totalArchived, totalDeleted);

        } catch (Exception e) {
            log.error("[NotificationRetentionBatch] 失敗: 移送済み={}件", totalArchived, e);
            throw e;
        }
    }

    /**
     * 移送対象を archive へ INSERT する（1チャンク・独立コミット）。
     * {@code INSERT IGNORE} により id 重複（再移送）で衝突しない＝冪等。
     */
    int insertIntoArchive(LocalDateTime readThreshold, LocalDateTime unreadThreshold) {
        Integer archived = transactionTemplate.execute(status -> jdbcTemplate.update(
                "INSERT IGNORE INTO notifications_archive " +
                "  (id, user_id, organization_id, notification_type, priority, title, body, " +
                "   source_type, source_id, scope_type, scope_id, action_url, actor_id, " +
                "   is_read, read_at, channels_sent, snoozed_until, created_at, archived_at) " +
                "SELECT id, user_id, organization_id, notification_type, priority, title, body, " +
                "       source_type, source_id, scope_type, scope_id, action_url, actor_id, " +
                "       is_read, read_at, channels_sent, snoozed_until, created_at, NOW() " +
                "FROM notifications " +
                "WHERE " + MOVE_PREDICATE + " " +
                "ORDER BY created_at ASC " +
                "LIMIT ?",
                readThreshold, unreadThreshold, BATCH_SIZE));
        return archived == null ? 0 : archived;
    }

    /**
     * archive に入った id のみ本体から削除する（1チャンク・独立コミット）。
     * {@code id IN (SELECT id FROM notifications_archive)} を安全弁とし、
     * archive 未収録の行を本体から消さない（欠落防止）。
     */
    int deleteArchived(LocalDateTime readThreshold, LocalDateTime unreadThreshold, int limit) {
        Integer deleted = transactionTemplate.execute(status -> jdbcTemplate.update(
                "DELETE FROM notifications " +
                "WHERE " + MOVE_PREDICATE + " " +
                "  AND id IN (SELECT id FROM notifications_archive) " +
                "LIMIT ?",
                readThreshold, unreadThreshold, limit));
        return deleted == null ? 0 : deleted;
    }
}
