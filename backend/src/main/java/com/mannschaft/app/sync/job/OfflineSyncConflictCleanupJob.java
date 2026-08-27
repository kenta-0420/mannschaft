package com.mannschaft.app.sync.job;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.sync.repository.OfflineSyncConflictRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F11.1 オフライン同期: コンフリクト清掃バッチ。
 * 毎日 AM4:00（JST）に実行し、解決済みかつ90日以上経過したコンフリクトレコードを物理削除する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfflineSyncConflictCleanupJob {

    private static final int RETENTION_DAYS = 90;

    private final OfflineSyncConflictRepository conflictRepository;

    /**
     * 解決済みコンフリクトの清掃。
     * resolution IS NOT NULL かつ resolved_at が90日以上前のレコードを物理削除する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_WEBHOOK_SYNC_ENABLED",
            reason = "止めても解決済みコンフリクト行が残って肥大するだけで、法定保持義務も無く再開後に同じ 90 日条件で削除し直せる")
    @BatchEndpoint(name = "sync-offline-conflict-cleanup-daily", description = "解決済みオフライン同期コンフリクト 90 日経過分を毎日 04:00 に物理削除する")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "offlineSyncConflictCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupResolvedConflicts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = conflictRepository.deleteByResolvedAtBeforeAndResolutionIsNotNull(cutoff);

        if (deleted > 0) {
            log.info("[OfflineSyncConflictCleanup] 解決済みコンフリクト {}件を物理削除しました（基準日: {}）",
                    deleted, cutoff);
        } else {
            log.info("[OfflineSyncConflictCleanup] 削除対象なし。スキップします");
        }
    }
}
