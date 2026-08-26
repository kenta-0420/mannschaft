package com.mannschaft.app.auth.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * audit_logs パーティション保守バッチ。
 *
 * <p>毎月1日 AM 1:00 に翌々月分のパーティションを追加する。
 * {@code REORGANIZE PARTITION p_future} で既存データを保持したまま分割する。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎月1日 AM 1:00 JST に実行（アーカイブバッチの1時間前）</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>翌月と翌々月の2か月分を先行作成することで、バッチ失敗時の猶予を確保する</li>
 *   <li>既存パーティションは {@code information_schema.PARTITIONS} で確認し、二重追加を防止する</li>
 *   <li>p_future は常に MAXVALUE を保持し、想定外の年月のデータを受け止める</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogPartitionMaintenanceBatchService {

    private final JdbcTemplate jdbcTemplate;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると audit_logs の翌月パーティションが作られず、挿入先が枯渇して監査ログの書き込み自体が失敗する（記録の欠落は事後復元できない）")
    @BatchEndpoint(name = "auth-audit-log-partition-maintenance", description = "audit_logs テーブルの翌々月分パーティションを毎月 1 日 01:00 に追加する")
    @Scheduled(cron = "0 0 1 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "auditLogPartitionMaintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void addNextPartitions() {
        // 翌月と翌々月のパーティションを追加（余裕を持って2か月先まで準備）
        YearMonth next1 = YearMonth.now().plusMonths(1);
        YearMonth next2 = YearMonth.now().plusMonths(2);

        addPartitionIfNeeded(next1);
        addPartitionIfNeeded(next2);
    }

    /**
     * 指定年月のパーティションが存在しない場合のみ追加する。
     * p_future を分割して新しい月パーティションを挿入する。
     *
     * @param ym 追加対象の年月
     */
    void addPartitionIfNeeded(YearMonth ym) {
        String partitionName = String.format("p_%d_%02d", ym.getYear(), ym.getMonthValue());
        YearMonth next = ym.plusMonths(1);
        String nextBoundary = next.getYear() + "-" + String.format("%02d", next.getMonthValue()) + "-01";

        // 既に存在するか確認
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PARTITIONS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' AND PARTITION_NAME = ?",
                Integer.class, partitionName);

        if (count != null && count > 0) {
            log.debug("[AuditLogPartitionMaintenance] パーティション既存のためスキップ: {}", partitionName);
            return;
        }

        String sql = String.format(
                "ALTER TABLE audit_logs REORGANIZE PARTITION p_future INTO (" +
                "  PARTITION %s VALUES LESS THAN (TO_DAYS('%s'))," +
                "  PARTITION p_future VALUES LESS THAN MAXVALUE" +
                ")", partitionName, nextBoundary);

        jdbcTemplate.execute(sql);
        log.info("[AuditLogPartitionMaintenance] パーティション追加完了: {}", partitionName);
    }
}
