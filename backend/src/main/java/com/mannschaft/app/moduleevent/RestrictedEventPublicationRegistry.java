package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactResolverRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 保存対象の制約と試行台帳を接続する Registry。 */
public class RestrictedEventPublicationRegistry extends DefaultEventPublicationRegistry {
    // cutoffとは別の実行猶予。初期値は負荷試験で処理時間との関係を検証する。
    private static final Duration INITIAL_LEASE = Duration.ofMinutes(5);
    private final EventPublicationRepository repository;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ModuleEventListenerCatalog catalog;
    private final ObjectProvider<ModuleEventImpactResolverRegistry> impacts;
    private final TransactionTemplate completionTransaction;
    private final ConcurrentMap<InvocationKey, Generation> generations = new ConcurrentHashMap<>();

    public RestrictedEventPublicationRegistry(EventPublicationRepository repository, Clock clock,
            EntityManager entityManager, ModuleEventListenerCatalog catalog,
            PlatformTransactionManager transactions, ObjectProvider<ModuleEventImpactResolverRegistry> impacts) {
        super(repository, clock);
        this.repository = repository;
        this.clock = clock;
        this.entityManager = entityManager;
        this.catalog = catalog;
        // 起動時は業務resolverを生成せず、保存hookの純識別時だけ解決する。
        this.impacts = impacts;
        completionTransaction = new TransactionTemplate(transactions);
        completionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 公式保存の前に対象を絞り、本表と補助行を発行元TXで保存する。 */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public Collection<TargetEventPublication> store(Object event, Stream<PublicationTargetIdentifier> targets) {
        if (!(event instanceof ReliableEvent)) {
            return List.of();
        }
        if (!catalog.isReady()) {
            throw new IllegalStateException("確実配信リスナー台帳が未初期化です");
        }
        List<PublicationTargetIdentifier> selected = targets.filter(target -> isTarget(event, target)).toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("確実配信イベントは業務TX内で発行してください");
        }
        // 対象と業務TXを確認した後、公式保存前に純識別を一回だけ行う。
        ModuleEventImpactBinding impact = impacts.getObject().identify((ReliableEvent) event).orElse(null);
        Collection<TargetEventPublication> publications = super.store(event, selected.stream());
        for (TargetEventPublication publication : publications) {
            InvocationKey key = new InvocationKey(event, publication.getTargetIdentifier());
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), UserZoneLocalDateTimeParser.SERVER_ZONE);
            UUID token = UUID.randomUUID();
            EventPublicationAttemptEntity attempt = EventPublicationAttemptEntity.builder()
                    .publicationId(publication.getIdentifier()).retryCount(0)
                    .status(ModuleEventAttemptStatus.IN_PROGRESS).claimToken(token)
                    .lastAttemptAt(now).leaseUntil(now.plus(INITIAL_LEASE))
                    .resolverKey(impact == null ? null : impact.getResolverKey())
                    .operationId(impact == null ? null : impact.getOperationId())
                    .episodeId(impact == null ? null : impact.getEpisodeId())
                    .scopeType(impact == null ? null : impact.getScopeType())
                    .scopeId(impact == null ? null : impact.getScopeId())
                    .organizationId(impact == null ? null : impact.getOrganizationId())
                    .createdAt(now).updatedAt(now).build();
            entityManager.persist(attempt);
            Generation generation = new Generation(publication.getIdentifier(), attempt.getId(), token);
            if (generations.putIfAbsent(key, generation) != null) {
                throw new IllegalStateException("同じイベント実体と購読IDの同時発行はできません");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // commit時はAFTER_COMMITの非同期submitより先に対応を消さない。
                    if (status != STATUS_COMMITTED) {
                        generations.remove(key, generation);
                        unregister(event, publication.getTargetIdentifier());
                    }
                }
            });
        }
        return publications;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void markCompleted(Object event, PublicationTargetIdentifier target) {
        finish(event, target, true);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void markFailed(Object event, PublicationTargetIdentifier target) {
        finish(event, target, false);
    }

    private void finish(Object event, PublicationTargetIdentifier target, boolean completed) {
        if (!isTarget(event, target)) {
            return;
        }
        InvocationKey key = new InvocationKey(event, target);
        Generation generation = generations.get(key);
        if (generation == null) {
            return;
        }
        completionTransaction.executeWithoutResult(status -> {
            EventPublicationAttemptEntity attempt = entityManager.find(EventPublicationAttemptEntity.class,
                    generation.attemptId(), LockModeType.PESSIMISTIC_WRITE);
            if (attempt == null || attempt.getStatus() != ModuleEventAttemptStatus.IN_PROGRESS
                    || !generation.token().equals(attempt.getClaimToken())
                    || !generation.publicationId().equals(attempt.getPublicationId())) {
                return;
            }
            if (completed) {
                // eventの同値検索へfallbackせず、取得した世代のUUIDだけを完了する。
                repository.markCompleted(generation.publicationId(), clock.instant());
                entityManager.remove(attempt);
            } else {
                attempt.recordInitialFailure(LocalDateTime.ofInstant(clock.instant(),
                        UserZoneLocalDateTimeParser.SERVER_ZONE));
            }
        });
        generations.remove(key, generation);
        unregister(event, target);
    }

    private boolean isTarget(Object event, PublicationTargetIdentifier target) {
        return event instanceof ReliableEvent && catalog.isReady()
                && catalog.eventType(target.getValue()).filter(type -> type.isInstance(event)).isPresent();
    }

    private void unregister(Object event, PublicationTargetIdentifier target) {
        // 公式markFailedはメモリ内inProgressの解除のみで、保存・イベント検索を行わない。
        super.markFailed(event, target);
    }

    private record Generation(UUID publicationId, UUID attemptId, UUID token) {
    }

    /** 同値recordを混同せず、公式trackerと同じ実体＋対象IDの境界を保つ。 */
    private static final class InvocationKey {
        private final Object event;
        private final PublicationTargetIdentifier target;

        private InvocationKey(Object event, PublicationTargetIdentifier target) {
            this.event = event;
            this.target = target;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof InvocationKey key && event == key.event && target.equals(key.target);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(event) + target.hashCode();
        }
    }

    /** 終了時に未完了 payload を全件取り込まない。観測は集計クエリが担う。 */
    @Override
    public void destroy() {
    }
}
