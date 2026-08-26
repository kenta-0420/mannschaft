package com.mannschaft.app.analytics.service;

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
 * ページビュー生ログ {@code page_view_logs} のパーティション保守バッチ（F10.8 アクセス解析）。
 *
 * <p>毎月 1 日 AM 1:00 (JST) に翌々月分のパーティションを追加し、保持期間（13 ヶ月・設計書 §9）を
 * 超えた古いパーティションを DROP する。手本は {@code AuditLogPartitionMaintenanceBatchService}
 * （{@code REORGANIZE PARTITION p_future} で既存データを保持したまま分割）。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎月 1 日 AM 1:00 JST。翌月・翌々月の 2 か月分を先行作成し、バッチ失敗時の猶予を確保する</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>{@code p_future}（MAXVALUE）は常に維持し、想定外年月のデータを受け止める</li>
 *   <li>既存パーティションは {@code information_schema.PARTITIONS} で確認し二重追加を防止する（AC-21）</li>
 *   <li>DROP は保持期間（13 ヶ月）より古い月次パーティションのみ。日次/月次集計テーブルには恒久データが残る</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageViewPartitionMaintenanceBatchService {

    private static final String TABLE_NAME = "page_view_logs";

    /** 生ログ保持期間（月）。この月数より古いパーティションは DROP する（設計書 §9）。 */
    private static final int RETENTION_MONTHS = 13;

    private final JdbcTemplate jdbcTemplate;

    /**
     * パーティション保守バッチ本体。翌月・翌々月分を追加し、保持期間超過分を DROP する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると page_views の翌月パーティションが作られず、挿入先が枯渇して閲覧ログの書き込み自体が失敗する（単なる集計遅延では済まない）")
    @BatchEndpoint(
            name = "analytics-pageview-partition-maintenance",
            description = "page_view_logs テーブルの翌々月分パーティションを毎月 1 日 01:00 に追加し、保持期間(13ヶ月)超過分を DROP する")
    @Scheduled(cron = "0 0 1 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "analyticsPageViewPartitionMaintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void maintainPartitions() {
        YearMonth next1 = YearMonth.now().plusMonths(1);
        YearMonth next2 = YearMonth.now().plusMonths(2);
        addPartitionIfNeeded(next1);
        addPartitionIfNeeded(next2);
        dropExpiredPartitions();
    }

    /**
     * 指定年月のパーティションが存在しない場合のみ追加する。
     * {@code p_future} を分割して新しい月パーティションを挿入する（AC-21）。
     *
     * @param ym 追加対象の年月
     */
    void addPartitionIfNeeded(YearMonth ym) {
        String partitionName = partitionName(ym);
        YearMonth next = ym.plusMonths(1);
        String nextBoundary = next.getYear() + "-" + String.format("%02d", next.getMonthValue()) + "-01";

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PARTITIONS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME = ?",
                Integer.class, TABLE_NAME, partitionName);

        if (count != null && count > 0) {
            log.debug("[PageViewPartitionMaintenance] パーティション既存のためスキップ: {}", partitionName);
            return;
        }

        String sql = String.format(
                "ALTER TABLE %s REORGANIZE PARTITION p_future INTO (" +
                        "  PARTITION %s VALUES LESS THAN (TO_DAYS('%s'))," +
                        "  PARTITION p_future VALUES LESS THAN MAXVALUE" +
                        ")", TABLE_NAME, partitionName, nextBoundary);

        jdbcTemplate.execute(sql);
        log.info("[PageViewPartitionMaintenance] パーティション追加完了: {}", partitionName);
    }

    /**
     * 保持期間（13 ヶ月）より古い月次パーティションを DROP する。
     * {@code p_future} は対象外（常に維持）。
     */
    void dropExpiredPartitions() {
        YearMonth cutoff = YearMonth.now().minusMonths(RETENTION_MONTHS);
        String cutoffPartition = partitionName(cutoff);

        // p_future 以外の月次パーティションのうち、cutoff 以前の名前を持つものを列挙して DROP。
        // パーティション名は p_YYYY_MM 形式（文字列比較で年月順が保たれる）。
        var expired = jdbcTemplate.queryForList(
                "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                        "  AND PARTITION_NAME IS NOT NULL " +
                        "  AND PARTITION_NAME <> 'p_future' " +
                        "  AND PARTITION_NAME < ? " +
                        "ORDER BY PARTITION_NAME ASC",
                String.class, TABLE_NAME, cutoffPartition);

        for (String partitionName : expired) {
            String sql = String.format("ALTER TABLE %s DROP PARTITION %s", TABLE_NAME, partitionName);
            jdbcTemplate.execute(sql);
            log.info("[PageViewPartitionMaintenance] 保持期間超過パーティションを DROP: {}", partitionName);
        }
    }

    /**
     * 年月からパーティション名（{@code p_YYYY_MM}）を組み立てる（DDL・audit_logs と同書式）。
     */
    private String partitionName(YearMonth ym) {
        return String.format("p_%d_%02d", ym.getYear(), ym.getMonthValue());
    }
}
