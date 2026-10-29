package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactResolverRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 公式trackerとimmutable世代を同じ所有境界で接続するRegistry。 */
public class RestrictedEventPublicationRegistry extends DefaultEventPublicationRegistry
        implements ModuleEventExecutionFrame.Owner {
    private static final Duration REPLAY_CUTOFF = Duration.ofMinutes(5);
    private final Clock clock;
    private final ModuleEventListenerCatalog catalog;
    private final ObjectProvider<ModuleEventImpactResolverRegistry> impacts;
    private final ModuleEventExecutionFrame.Context frames;
    private final ModuleEventPublicationStore store;
    private final ObjectProvider<ModuleEventTaskExecutor> executor;
    private final ThreadLocal<List<TargetEventPublication>> bounded = new ThreadLocal<>();

    public RestrictedEventPublicationRegistry(EventPublicationRepository repository, Clock clock,
            ModuleEventListenerCatalog catalog, ObjectProvider<ModuleEventImpactResolverRegistry> impacts,
            ModuleEventExecutionFrame.Context frames, ModuleEventPublicationStore store,
            ObjectProvider<ModuleEventTaskExecutor> executor) {
        super(repository, clock);
        this.clock = clock;
        this.catalog = catalog;
        this.impacts = impacts;
        this.frames = frames;
        this.store = store;
        this.executor = executor;
    }

    /** 全対象をpreflightしてから、公式本表・helper・frameを同publisher TXへ結ぶ。 */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public Collection<TargetEventPublication> store(Object event, Stream<PublicationTargetIdentifier> targets) {
        if (!(event instanceof ReliableEvent reliable)) return List.of();
        if (!catalog.isReady()) throw new IllegalStateException("確実配信リスナー台帳が未初期化です");
        List<PublicationTargetIdentifier> selected = targets.filter(target -> isTarget(event, target)).toList();
        if (selected.isEmpty()) return List.of();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("確実配信イベントは書込業務TX内で発行してください");
        }
        ModuleEventImpactBinding impact = impacts.getObject().identify(reliable).orElse(null);
        synchronized (frames) {
            frames.assertUnregistered(event, selected.stream().map(PublicationTargetIdentifier::getValue).toList());
            List<ModuleEventExecutionFrame> registered = new ArrayList<>();
            // 公式createの途中失敗や遅延flush失敗も、登録前からrollbackを観測する。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) cleanupFrames(registered);
                }
            });
            try {
                Collection<TargetEventPublication> publications = super.store(event, selected.stream());
                for (TargetEventPublication publication : publications) {
                    UUID token = UUID.randomUUID();
                    EventPublicationAttemptEntity attempt = store.createInitialAttempt(
                            publication.getIdentifier(), token, impact);
                    ModuleEventExecutionFrame frame = new ModuleEventExecutionFrame(event,
                            publication.getIdentifier(), attempt.getId(), token,
                            publication.getTargetIdentifier().getValue(), true);
                    frames.register(frame);
                    registered.add(frame);
                }
                return publications;
            } catch (RuntimeException | Error failure) {
                // super.storeはcreate→register逐次で、途中throw時にはCollectionが返らない。
                // preflightで他owner不在を確認済みの全selectedだけを同monitor内で掃除する。
                for (PublicationTargetIdentifier target : selected) {
                    ModuleEventExecutionFrame frame = registered.stream()
                            .filter(candidate -> candidate.listenerId().equals(target.getValue())).findFirst().orElse(null);
                    try {
                        if (frame == null) unregister(event, target);
                        else frames.release(frame, () -> unregister(event, target));
                    } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                }
                throw failure;
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void markCompleted(Object event, PublicationTargetIdentifier target) { finish(event, target, true); }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void markFailed(Object event, PublicationTargetIdentifier target) { finish(event, target, false); }

    private void finish(Object event, PublicationTargetIdentifier target, boolean completed) {
        // 認証・通常イベントを再serialize/lookupしない。callbackはworkerが運んだ旧tokenだけ。
        if (!isTarget(event, target)) return;
        ModuleEventExecutionFrame frame = frames.execution().orElse(null);
        if (frame == null || frame.event() != event || !frame.listenerId().equals(target.getValue())) return;
        store.finish(frame, completed);
    }

    @Override public boolean admit(ModuleEventExecutionFrame frame) { return store.admit(frame); }

    @Override public void rejected(ModuleEventExecutionFrame frame) {
        try {
            store.rejected(frame);
        } catch (RuntimeException | Error failure) {
            try { finished(frame); } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
            throw failure;
        }
        finished(frame);
    }

    @Override public void finished(ModuleEventExecutionFrame frame) {
        frames.release(frame, () -> unregister(frame.event(), PublicationTargetIdentifier.of(frame.listenerId())));
    }

    /** 公式のtarget consumerとinProgress登録を使い、全ロードだけをcall-scoped listへ置き換える。 */
    @Override
    public void processIncompletePublications(Predicate<EventPublication> filter,
            Consumer<TargetEventPublication> consumer, Duration olderThan) {
        ModuleEventTaskExecutor pool = executor.getObject();
        // 50/200はM18で未確定の初期値。予約はSQLより前、全exitで未使用slotを返す。
        try (var budget = pool.reserveReplayBudget(100, 50)) {
            if (budget.size() == 0) return;
            Instant now = clock.instant();
            Instant cutoff = olderThan == null ? now.minus(REPLAY_CUTOFF) : now.minus(olderThan);
            List<ModuleEventPublicationStore.Claim> claims = store.claim(cutoff, now, budget.size());
            Map<UUID, ModuleEventExecutionFrame> byPublication = new HashMap<>();
            Set<UUID> dispatched = new HashSet<>();
            List<TargetEventPublication> previous = bounded.get();
            Throwable original = null;
            synchronized (frames) {
                try {
                    for (ModuleEventPublicationStore.Claim claim : claims) {
                        ModuleEventExecutionFrame frame = claim.frame();
                        frames.expirePriorGenerations(frame.publicationId(), frame.token(), old ->
                                unregister(old.event(), PublicationTargetIdentifier.of(old.listenerId())));
                        frames.assertUnregistered(frame.event(), List.of(frame.listenerId()));
                        frames.register(frame);
                        byPublication.put(frame.publicationId(), frame);
                    }
                    bounded.set(claims.stream().map(ModuleEventPublicationStore.Claim::publication).toList());
                    // private trackerのregister/catch-unregisterもこのmonitorの内側で直列化する。
                    super.processIncompletePublications(filter, publication -> {
                        ModuleEventExecutionFrame frame = byPublication.get(publication.getIdentifier());
                        dispatched.add(publication.getIdentifier());
                        try (var slot = budget.submitting(frame)) {
                            consumer.accept(publication);
                            if (!frames.wasCaptured(frame)) rejected(frame);
                        } catch (RuntimeException | Error failure) {
                            try { rejected(frame); } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                            throw failure;
                        }
                    }, olderThan);
                } catch (RuntimeException | Error failure) {
                    original = failure;
                    throw failure;
                } finally {
                    if (previous == null) bounded.remove();
                    else bounded.set(previous);
                    Throwable cleanupFailure = original;
                    // filter除外・登録途中失敗・consumer未到達をlease待ちや強参照漏れにしない。
                    for (ModuleEventPublicationStore.Claim claim : claims) {
                        if (dispatched.contains(claim.frame().publicationId())) continue;
                        try { rejected(claim.frame()); }
                        catch (RuntimeException | Error cleanup) {
                            if (cleanupFailure == null) cleanupFailure = cleanup;
                            else suppress(cleanupFailure, cleanup);
                        }
                    }
                    if (original == null) rethrow(cleanupFailure);
                }
            }
        }
    }

    /** processIncompletePublications以外から公式全件Repositoryへfallbackしない。 */
    @Override public Collection<TargetEventPublication> findIncompletePublications() {
        List<TargetEventPublication> current = bounded.get();
        return current == null ? List.of() : current;
    }
    @Override public Collection<TargetEventPublication> findIncompletePublicationsOlderThan(Duration duration) {
        return findIncompletePublications();
    }

    private void cleanupFrames(List<ModuleEventExecutionFrame> registered) {
        synchronized (frames) {
            Throwable failure = null;
            for (ModuleEventExecutionFrame frame : registered) {
                try { finished(frame); }
                catch (RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else suppress(failure, cleanup);
                }
            }
            rethrow(failure);
        }
    }
    private boolean isTarget(Object event, PublicationTargetIdentifier target) {
        return event instanceof ReliableEvent && catalog.isReady()
                && catalog.eventType(target.getValue()).filter(type -> type == event.getClass()).isPresent();
    }
    private void unregister(Object event, PublicationTargetIdentifier target) { super.markFailed(event, target); }
    private static void suppress(Throwable original, Throwable cleanup) {
        if (original != cleanup) original.addSuppressed(cleanup);
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
    /** shutdown時にpayloadを全件ロードしない。 */
    @Override public void destroy() { }
}
