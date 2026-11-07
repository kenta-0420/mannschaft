package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 毎分の再投入入口。短TXと非同期業務の完了をこのlockへ抱え込まない。 */
@Component
public class ModuleEventReplayBatch {
    public static final String LOCK_NAME = "moduleEventReplayBatch";
    public static final String BATCH_NAME = "module-event-replay";
    private final IncompleteEventPublications publications;
    private final ModuleEventOperations operations;

    public ModuleEventReplayBatch(IncompleteEventPublications publications, ModuleEventOperations operations) {
        this.publications = publications;
        this.operations = operations;
    }

    /** lock猶予はM18未確定の初期値。公式再投入と運用観測を同じlock内で開始する。 */
    @BatchEndpoint(name = BATCH_NAME, description = "未完了の確実配信を毎分有界に再投入する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "永続済み未完了イベントの再配信主体であり、止めると業務間の未反映と遅延が残り続ける")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT3M")
    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        LockAssert.assertLocked();
        publications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
        operations.monitor(Instant.now());
    }
}
