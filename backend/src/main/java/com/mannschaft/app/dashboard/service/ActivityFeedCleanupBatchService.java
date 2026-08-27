package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * {@code activity_feed} 保持期間超過行の日次物理削除バッチ（F03.18 §10.5・検分是正・重大8）。
 *
 * <p>{@link ActivityFeedRepository#deleteByCreatedAtBefore} はテーブル新設当初から定義済みだが
 * 呼び出し元が無く、`activity_feed` は 30 日保持の設計（§10.1）にもかかわらず実際には
 * 無期限に肥大し続けていた。本クラスはその呼び出し元を新設し、毎日 AM 3:00 JST に
 * 30 日超のレコードを物理削除する。対象は SCHEDULE 系 4 種別に限らず `activity_feed` 全体
 * （既存 7 種別含む）であり、テーブル単位の保持ポリシーとして扱う（§10.5）。</p>
 *
 * <p><b>「今」の時間基準:</b> {@code ActivityFeedEntity#createdAt} は {@code @PrePersist} で
 * {@code LocalDateTime.now()}（JVM 既定ゾーン基準の壁時計）を書き込む。これと同じ基準で
 * 閾値を計算しないと、既定ゾーンが UTC でない環境（本番・開発機ともに JST）で削除境界が
 * オフセット分ずれる（{@code ClockConfig#wallClock} Javadoc 参照）。そのため既定の UTC 固定
 * Clock（{@code ClockConfig#utcClock}）ではなく、明示的に壁時計 Bean
 * {@code ClockConfig#wallClock} を注入して用いる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityFeedCleanupBatchService {

    /** 保持日数（§10.1「現行30日保持を踏襲」）。 */
    static final int RETENTION_DAYS = 30;

    private final ActivityFeedRepository activityFeedRepository;

    /** 業務ローカル時刻の壁時計（{@code ClockConfig#wallClock}）。createdAt と同一の時間基準。 */
    @Qualifier("wallClock")
    private final Clock clock;

    /**
     * 30 日超の {@code activity_feed} 行を物理削除する。毎日 03:00 JST に実行する。
     */
    @BatchEndpoint(name = "activity-feed-cleanup-daily",
            description = "activity_feed の30日超レコードを毎日 AM 3:00 に物理削除する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。古いアクティビティフィードの削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "activityFeedCleanupBatch", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupOldActivityFeed() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusDays(RETENTION_DAYS);
        int deleted = activityFeedRepository.deleteByCreatedAtBefore(threshold);
        log.info("activity_feed 物理削除バッチ完了: threshold={}, deletedCount={}", threshold, deleted);
    }
}
