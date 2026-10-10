package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 取り込み済み・死んだ outbox の行を掃除する（docs/architecture/notification_outbox.md §4.5）。日次。
 *
 * <p>RELAYED は {@code relayed_at} から7日、DEAD は {@code dead_at} から30日を過ぎたものを削除する
 * （起算点は {@code created_at} ではない）。PENDING・RELAYING は消さない。tx を持たず、各 source の
 * {@link NotificationOutboxSource#sweep} を呼ぶ。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxSweepBatch {

    /** バッチ一覧 API の名前。 */
    public static final String BATCH_NAME = "notification-outbox-sweep";
    /** RELAYED の保存期間（{@code relayed_at} から）。 */
    public static final Duration RELAYED_RETENTION = Duration.ofDays(7);
    /** DEAD の保存期間（{@code dead_at} から。調査のため長めに残す）。 */
    public static final Duration DEAD_RETENTION = Duration.ofDays(30);

    private final List<NotificationOutboxSource> sources;
    private final Clock clock;

    /** 保存期間を過ぎた RELAYED・DEAD を削除する。 */
    @Scheduled(cron = "0 40 4 * * *")
    @SchedulerLock(name = "notificationOutboxSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = BATCH_NAME,
            description = "取り込み済みから7日・DEADから30日を過ぎた通知outboxの行を削除する（日次 04:40）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると取り込み済みの outbox 行が際限なく溜まり、claim と最古の PENDING の問い合わせが遅くなる")
    public void sweep() {
        Instant now = Instant.now(clock);
        for (NotificationOutboxSource source : sources) {
            int deleted = source.sweep(now.minus(RELAYED_RETENTION), now.minus(DEAD_RETENTION));
            if (deleted > 0) {
                log.info("通知 outbox の保存期間を過ぎた行 {} 件を削除した: source={}", deleted, source.name());
            }
        }
    }
}
