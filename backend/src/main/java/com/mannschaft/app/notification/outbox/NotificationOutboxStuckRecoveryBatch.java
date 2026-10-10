package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * claim されたまま止まった outbox の行（RELAYING で {@code claimed_at} が2分を超えたもの）を PENDING に戻す
 * （docs/architecture/notification_outbox.md §4.4）。毎分。
 *
 * <p>tx を持たず、各 source の {@link NotificationOutboxSource#recoverStuck}（送り手の REQUIRES_NEW）を呼ぶ。
 * 回収した件数をメトリクス {@code recovered{source}} に足す。戻した行は claim_token を手放しているので、
 * 止まっていた relay が後から印を付けようとしても当たらない（OB08b）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxStuckRecoveryBatch {

    /** バッチ一覧 API の名前。 */
    public static final String BATCH_NAME = "notification-outbox-stuck-recovery";
    /** claim からこの時間を過ぎた RELAYING は止まったものとみなす（1回の drain の打ち切り30秒より十分長い）。 */
    public static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);

    private final List<NotificationOutboxSource> sources;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Clock clock;

    /** RELAYING の残骸を PENDING に戻す。 */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "notificationOutboxStuckRecovery", lockAtMostFor = "PT3M", lockAtLeastFor = "PT5S")
    @BatchEndpoint(name = BATCH_NAME,
            description = "claimから2分を超えてRELAYINGのまま止まった通知outboxの行をPENDINGに戻し再取り込みさせる（毎分）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると relay が落ちた行が RELAYING のまま残り、その通知は二度と取り込まれず利用者に届かない")
    public void recover() {
        Instant now = Instant.now(clock);
        Instant claimedBefore = now.minus(CLAIM_TIMEOUT);
        for (NotificationOutboxSource source : sources) {
            int recovered = source.recoverStuck(claimedBefore, now);
            if (recovered > 0) {
                log.warn("通知 outbox の RELAYING の残骸 {} 件を PENDING に戻した: source={} claimedBefore={}",
                        recovered, source.name(), claimedBefore);
                MeterRegistry registry = meterRegistryProvider.getIfAvailable();
                if (registry != null) {
                    Counter.builder(NotificationOutboxRelay.METRIC_RECOVERED)
                            .tag(NotificationOutboxRelay.TAG_SOURCE, source.name())
                            .register(registry)
                            .increment(recovered);
                }
            }
        }
    }
}
