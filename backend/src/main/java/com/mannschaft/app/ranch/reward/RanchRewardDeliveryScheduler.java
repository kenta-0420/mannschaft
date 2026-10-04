package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 明示的に有効化・計測設定された環境だけで、四源の配送を一回ずつ起動する。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "mannschaft.ranch.delivery.worker", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RanchRewardDeliveryScheduler {
    private final RanchRewardDeliveryOrchestrator orchestrator;
    private final Environment environment;

    @PostConstruct
    void validateRegistration() {
        try {
            long interval = Long.parseLong(environment.getRequiredProperty(
                    "mannschaft.ranch.delivery.worker.interval-ms"));
            Duration lock = Duration.parse(environment.getRequiredProperty(
                    "mannschaft.ranch.delivery.worker.lock-at-most"));
            if (interval <= 0 || lock.compareTo(Duration.ofMillis(interval)) <= 0) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("報酬配送workerの明示計測設定が不足または不正です", invalid);
        }
    }

    @Scheduled(fixedDelayString = "${mannschaft.ranch.delivery.worker.interval-ms}")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "明示有効化した源outboxの既存leaseを回収し、停止期間を跨ぐfactの再配達を維持する")
    @SchedulerLock(name = "ranchRewardDelivery",
            lockAtMostFor = "${mannschaft.ranch.delivery.worker.lock-at-most}")
    @BatchEndpoint(name = "ranch-reward-delivery",
            description = "承認済み四源の報酬配送を有界leaseと独立決定で処理する")
    public void poll() {
        try {
            var result = orchestrator.drainOnce();
            log.info("Ranch reward delivery leased={} acknowledged={} deferred={} retried={} failed={}",
                    result.leased(), result.acknowledged(), result.deferred(), result.retried(), result.failed());
        } catch (RuntimeException failure) {
            // 例外causeやsource本文を定型監視ログへ流さない。
            log.warn("Ranch reward delivery failed: RANCH_DELIVERY_RUN_FAILED");
        }
    }
}
