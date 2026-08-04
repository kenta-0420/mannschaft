package com.mannschaft.app.notification.fanout;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 通知 fan-out 耐久ジョブの RUNNING 残骸リカバリバッチ（P2・{@link EmailOutboxStuckRecoveryBatch} 金型）。
 *
 * <p>{@link NotificationFanoutWorker} がジョブを {@code RUNNING} にした後、チャンク配信の最中に pod が
 * クラッシュすると、行が {@code RUNNING} のまま放置され、以後どのワーカーも拾えなくなる
 *（{@code findReady} は PENDING/FAILED のみ対象）。本バッチは毎時 0 分に
 * {@code updated_at < NOW() - 5min} の {@code RUNNING} を {@code PENDING} に戻す。</p>
 *
 * <p>カーソル（{@code cursor_subject_id}）はチャンクごとに独立コミットされているため、PENDING へ戻した
 * ジョブの再開は「処理済みカーソルの直後」から続き、欠落なく完走する（AC-2 と整合）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFanoutStuckRecoveryBatch {

    private static final int STUCK_THRESHOLD_MINUTES = 5;

    /** 残骸回収カウンタ（可観測性・AC-10・P1 命名に整合）。 */
    static final String METRIC_STUCK_RECOVERED = "mannschaft.notification.fanout.job.stuck_recovered";

    private final NotificationFanoutJobRepository repository;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 毎時 0 分に RUNNING 残骸を PENDING に戻す。 */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "notificationFanoutStuckRecovery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Transactional
    public void recover() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        int recovered = repository.recoverStuckRunning(threshold);
        if (recovered > 0) {
            log.warn("通知 fan-out の RUNNING 残骸 {} 件を PENDING に回収（threshold={}）", recovered, threshold);
            MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter(METRIC_STUCK_RECOVERED).increment(recovered);
            }
        }
    }
}
