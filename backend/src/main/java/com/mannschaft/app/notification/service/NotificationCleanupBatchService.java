package com.mannschaft.app.notification.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 通知クリーンアップバッチ。
 *
 * <p>90日以上前の既読通知を物理削除し、{@code notifications} テーブルの肥大化を防ぐ。
 * 通知は法的保持義務がないため、既読後90日を超えたものは安全に削除できる。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎日 AM 4:00 JST（他アーカイブバッチと時間帯をずらす）</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>削除対象</h2>
 * <ul>
 *   <li>{@code is_read = true}（既読）</li>
 *   <li>{@code created_at < NOW() - 90日}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCleanupBatchService {

    private static final int RETENTION_DAYS = 90;
    private static final int BATCH_SIZE = 10_000;

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "notificationCleanupBatch", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    @BatchEndpoint(name = "notification-cleanup", description = "通知の物理削除（保持期限超過分）")
    public void cleanupOldReadNotifications() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        log.info("[NotificationCleanupBatch] クリーンアップ開始: 基準日時={}", threshold);

        long totalDeleted = 0;

        try {
            while (true) {
                int deleted = deleteOldReadNotifications(threshold);
                if (deleted == 0) break;

                totalDeleted += deleted;
                log.info("[NotificationCleanupBatch] バッチ削除: {}件（累計: {}件）", deleted, totalDeleted);

                if (deleted < BATCH_SIZE) break;
            }

            log.info("[NotificationCleanupBatch] 完了: 総削除={}件", totalDeleted);

        } catch (Exception e) {
            log.error("[NotificationCleanupBatch] 失敗: 削除済み={}件", totalDeleted, e);
        }
    }

    @Transactional
    public int deleteOldReadNotifications(LocalDateTime threshold) {
        return jdbcTemplate.update(
                "DELETE FROM notifications " +
                "WHERE is_read = TRUE AND created_at < ? " +
                "LIMIT ?",
                threshold, BATCH_SIZE);
    }
}
