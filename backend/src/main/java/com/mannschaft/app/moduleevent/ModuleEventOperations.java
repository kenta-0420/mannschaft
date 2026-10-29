package com.mannschaft.app.moduleevent;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定名の観測と運用signal。ID/payloadをlabelや公開契約へ含めない。操作はred前の骨格。 */
@Component
public class ModuleEventOperations {
    public static final String INCOMPLETE = "module_event.incomplete.count";
    public static final String OLDEST_AGE = "module_event.incomplete.oldest.age.seconds";
    public static final String REPLAY_ACCEPTED = "module_event.replay.accepted";
    public static final String QUARANTINED = "module_event.quarantined.count";
    public static final String PATTERN_F_FAILURE = "module_event.pattern_f.completion.failure";
    private final ModuleEventPublicationStore store;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher publisher;

    public ModuleEventOperations(ModuleEventPublicationStore store, MeterRegistry meters,
            ApplicationEventPublisher publisher) {
        this.store = store;
        this.meters = meters;
        this.publisher = publisher;
    }

    /** snapshotはPRIMARY短TXで取得する。payloadを取得せず全未完了状態を数える。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Observation observe(Instant now) {
        return new Observation(0L, Optional.empty(), 0L);
    }

    /** 最古がstrict30分超のときだけ固定kindを発行する。lease/cutoffを変更しない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void monitor(Instant now) { }

    /** Storeの受理commit後だけ呼ぶ。物理queue仮受理を数えない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordReplayAccepted() { }

    /** StoreのQ遷移commit後に一回呼ぶ。afterCommitの残留TXもproxyで停止する。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void quarantinedAfterCommit() { }

    /** 業務側の明示報告1回を1件とする。一般listener失敗から推定しない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordPatternFCompletionFailure() { }

    public record Observation(long incompleteCount, Optional<Instant> oldestIncomplete, long quarantinedCount) { }
}
