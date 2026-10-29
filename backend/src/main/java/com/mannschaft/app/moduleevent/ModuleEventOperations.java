package com.mannschaft.app.moduleevent;

import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeLifecycleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定名の観測と運用signal。ID/payloadをlabelや公開契約へ含めない。観測値と通知予約は別の短TXから取得する。 */
@Component
public class ModuleEventOperations {
    public static final String INCOMPLETE = "module_event.incomplete.count";
    public static final String OLDEST_AGE = "module_event.incomplete.oldest.age.seconds";
    public static final String REPLAY_ACCEPTED = "module_event.replay.accepted";
    public static final String QUARANTINED = "module_event.quarantined.count";
    public static final String PATTERN_F_FAILURE = "module_event.pattern_f.completion.failure";
    private final ModuleEventPublicationStore store;
    private final Counter replayAccepted;
    private final Counter patternFFailure;
    private final ObjectProvider<ModuleEventNoticeLifecycleService> notices;
    private volatile Observation observation = new Observation(0L, Optional.empty(), 0L);
    private volatile double oldestAgeSeconds;
    private final ApplicationEventPublisher publisher;

    public ModuleEventOperations(ModuleEventPublicationStore store, MeterRegistry meters,
            ApplicationEventPublisher publisher, ObjectProvider<ModuleEventNoticeLifecycleService> notices) {
        this.store = store;
        this.notices = notices;
        this.replayAccepted = meters.counter(REPLAY_ACCEPTED);
        this.patternFFailure = meters.counter(PATTERN_F_FAILURE);
        Gauge.builder(INCOMPLETE, this, value -> value.observation.incompleteCount()).register(meters);
        Gauge.builder(OLDEST_AGE, this, value -> value.oldestAgeSeconds).register(meters);
        Gauge.builder(QUARANTINED, this, value -> value.observation.quarantinedCount()).register(meters);
        this.publisher = publisher;
    }

    /** snapshotはPRIMARY短TXで取得する。payloadを取得せず全未完了状態を数える。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Observation observe(Instant now) {
        Objects.requireNonNull(now, "now");
        Observation current = store.observation();
        oldestAgeSeconds = current.oldestIncomplete()
                .map(oldest -> Math.max(0.0, Duration.between(oldest, now).toMillis() / 1000.0)).orElse(0.0);
        observation = current;
        return current;
    }

    /** 最古がstrict30分超のときだけ固定kindを発行する。lease/cutoffを変更しない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void monitor(Instant now) {
        Observation current = observe(now);
        if (current.oldestIncomplete().filter(oldest -> oldest.isBefore(now.minusSeconds(1800))).isPresent()) {
            publisher.publishEvent(new ModuleEventOperationalAlertEvent(
                    ModuleEventOperationalAlertEvent.Kind.OLDEST_PENDING_30_MIN));
        }
        ModuleEventNoticeLifecycleService lifecycle = notices.getObject();
        for (java.util.UUID attemptId : lifecycle.findDelayedCandidateIds(now, 100)) {
            lifecycle.reserveDelayed(attemptId, now);
        }
    }

    /** Storeの受理commit後だけ呼ぶ。物理queue仮受理を数えない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordReplayAccepted() { replayAccepted.increment(); }

    /** StoreのQ遷移commit後に一回呼ぶ。afterCommitの残留TXもproxyで停止する。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void quarantinedAfterCommit() {
        publisher.publishEvent(new ModuleEventOperationalAlertEvent(ModuleEventOperationalAlertEvent.Kind.QUARANTINED));
    }

    /** 業務側の明示報告1回を1件とする。一般listener失敗から推定しない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordPatternFCompletionFailure() { patternFFailure.increment(); }

    public record Observation(long incompleteCount, Optional<Instant> oldestIncomplete, long quarantinedCount) { }
}
