package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeLifecycleService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.NativeQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 有界SQL・codec・短TXの内部窓口。業務resolverを呼ばず、通知intentだけを同じ保存TXへ揃える。 */
public class ModuleEventPublicationStore {
    private static final Duration REPLAY_CUTOFF = Duration.ofMinutes(5);
    // cutoffとは別の猶予。M18で処理時間との関係を検証する初期値。
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(5);
    private final EntityManager entityManager;
    private final EventPublicationRepository repository;
    private final EventSerializer serializer;
    private final ModuleEventListenerCatalog catalog;
    private final Clock clock;
    private final ObjectProvider<ModuleEventOperations> operations;
    private final ObjectProvider<ModuleEventNoticeLifecycleService> notices;

    public ModuleEventPublicationStore(EntityManager entityManager, EventPublicationRepository repository,
            EventSerializer serializer, ModuleEventListenerCatalog catalog, Clock clock,
            ObjectProvider<ModuleEventOperations> operations, ObjectProvider<ModuleEventNoticeLifecycleService> notices) {
        this.entityManager = entityManager;
        this.repository = repository;
        this.serializer = serializer;
        this.catalog = catalog;
        this.clock = clock;
        this.operations = operations;
        this.notices = notices;
    }

    /** 初回補助保存はpublisherと同一物理TX。独立TXへ逃がさない。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public EventPublicationAttemptEntity createInitialAttempt(UUID publicationId, UUID token,
            ModuleEventImpactBinding binding) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("確実配信イベントの保存には書込TXが必要です");
        }
        Instant now = clock.instant();
        EventPublicationAttemptEntity attempt = EventPublicationAttemptEntity.builder()
                .publicationId(publicationId).retryCount(0).status(ModuleEventAttemptStatus.IN_PROGRESS)
                .claimToken(token).lastAttemptAt(now).leaseUntil(now.plus(EXECUTION_LEASE))
                .resolverKey(binding == null ? null : binding.getResolverKey())
                .operationId(binding == null ? null : binding.getOperationId())
                .episodeId(binding == null ? null : binding.getEpisodeId())
                .scopeType(binding == null ? null : binding.getScopeType())
                .scopeId(binding == null ? null : binding.getScopeId())
                .organizationId(binding == null ? null : binding.getOrganizationId())
                .createdAt(now).updatedAt(now).build();
        entityManager.persist(attempt);
        return attempt;
    }

    /** SQL前に確保済みの件数だけ取得する。cutoffとleaseの等号は回収対象。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public List<Claim> claim(Instant cutoff, Instant now, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("再配信上限は1から100です");
        if (!catalog.isReady()) throw new IllegalStateException("確実配信リスナー台帳が未初期化です");
        Instant latest = now.minus(REPLAY_CUTOFF);
        Instant effective = cutoff.isBefore(latest) ? cutoff : latest;
        // 本表と補助行はどちらもInstant/UTC。native境界でも瞬間を明示する。
        // STRAIGHT_JOINでhelperを先にlockし、JOIN optimizerのmain先行へ依存しない。
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT p.id AS publication_id, a.id AS attempt_id, p.listener_id AS listener_id,
                       p.event_type AS event_type, p.serialized_event AS serialized_event,
                       p.publication_date AS publication_date
                FROM event_publication_attempts a STRAIGHT_JOIN event_publication p ON p.id=a.publication_id
                WHERE p.completion_date IS NULL AND p.publication_date <= :cutoff
                  AND a.status IN ('PENDING','IN_PROGRESS')
                  AND (a.lease_until IS NULL OR a.lease_until <= :now)
                ORDER BY p.publication_date, p.id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).unwrap(NativeQuery.class)
                .addScalar("publication_id", byte[].class).addScalar("attempt_id", byte[].class)
                .addScalar("listener_id", String.class).addScalar("event_type", String.class)
                .addScalar("serialized_event", String.class).addScalar("publication_date", Instant.class);
        query.setParameter("cutoff", Timestamp.from(effective));
        query.setParameter("now", Timestamp.from(now));
        query.setParameter("limit", limit);
        List<Claim> result = new ArrayList<>();
        for (Object value : query.getResultList()) {
            Object[] row = (Object[]) value;
            UUID publicationId = uuid((byte[]) row[0]);
            EventPublicationAttemptEntity attempt = entityManager.find(EventPublicationAttemptEntity.class,
                    uuid((byte[]) row[1]), LockModeType.PESSIMISTIC_WRITE);
            Instant attemptedAt = now;
            if (attempt.getRetryCount() >= 10) {
                // 10回目の受理commit直後に停止した行も、11回目の業務へ入れず隔離する。
                quarantine(attempt, "RETRY_LIMIT", now);
                continue;
            }
            String listenerId = (String) row[2];
            Class<? extends ReliableEvent> type = catalog.eventType(listenerId).orElse(null);
            if (type == null || !type.isRecord() || !type.getName().equals(row[3])) {
                quarantine(attempt, "UNKNOWN_EVENT_TARGET", now);
                continue;
            }
            Object event;
            try {
                event = serializer.deserialize(row[4], type);
            } catch (RuntimeException failure) {
                // 例外本文やpayloadを保存せず、固定codeだけでpoisonを隔離する。
                quarantine(attempt, "EVENT_DESERIALIZATION_FAILURE", now);
                continue;
            }
            if (event == null || event.getClass() != type) {
                quarantine(attempt, "EVENT_TYPE_MISMATCH", now);
                continue;
            }
            UUID token = UUID.randomUUID();
            attempt.claim(token, attemptedAt, attemptedAt.plus(EXECUTION_LEASE));
            TargetEventPublication publication = new RestoredPublication(publicationId, event,
                    PublicationTargetIdentifier.of(listenerId), (Instant) row[5]);
            result.add(new Claim(publication, new ModuleEventExecutionFrame(event, publicationId,
                    attempt.getId(), token, listenerId, false)));
        }
        return List.copyOf(result);
    }

    /** worker入口直前のcommitが実行受理点。queue仮受理だけでは増やさない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public boolean admit(ModuleEventExecutionFrame frame) {
        EventPublicationAttemptEntity attempt = lock(frame);
        Instant now = clock.instant();
        if (attempt == null || attempt.getLeaseUntil() == null || !attempt.getLeaseUntil().isAfter(now)) return false;
        if (!frame.initial() && !attempt.admitReplay(frame.token(), now)) return false;
        attempt.renewLease(now, now.plus(EXECUTION_LEASE));
        if (!frame.initial()) afterCommit(() -> operations.getObject().recordReplayAccepted());
        return true;
    }

    /** completion/delete/helperをUUIDとtokenで限定し、同じ短TXに揃える。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public void finish(ModuleEventExecutionFrame frame, boolean completed) {
        EventPublicationAttemptEntity attempt = lock(frame);
        if (attempt == null) return;
        if (completed) {
            Instant now = clock.instant();
            ModuleEventImpactBinding binding = binding(attempt);
            if (binding != null) notices.getObject().beforeSuccessfulRemoval(binding, now);
            repository.markCompleted(frame.publicationId(), now);
            entityManager.remove(attempt);
        } else if (attempt.getRetryCount() >= 10) {
            quarantine(attempt, "LISTENER_FAILURE", clock.instant());
        } else {
            attempt.pending("LISTENER_FAILURE", clock.instant());
        }
    }

    /** 拒否・取消・consumer未投入は試行を増やさず、現tokenだけを回収可能へ戻す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public void rejected(ModuleEventExecutionFrame frame) {
        EventPublicationAttemptEntity attempt = lock(frame);
        if (attempt == null) return;
        if (attempt.getRetryCount() >= 10) quarantine(attempt, "RETRY_LIMIT", clock.instant());
        else attempt.pending("EXECUTOR_REJECTED", clock.instant());
    }

    /** 全未完了を集約するPRIMARY短TX。payloadやhelperの状態で母集団を落とさない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public ModuleEventOperations.Observation observation() {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*) AS incomplete_count, MIN(p.publication_date) AS oldest_incomplete,
                       COALESCE(SUM(CASE WHEN a.status='QUARANTINED' THEN 1 ELSE 0 END),0) AS quarantined_count
                FROM event_publication p LEFT JOIN event_publication_attempts a ON a.publication_id=p.id
                WHERE p.completion_date IS NULL
                """).unwrap(NativeQuery.class).addScalar("incomplete_count", Long.class)
                .addScalar("oldest_incomplete", Instant.class).addScalar("quarantined_count", Long.class)
                .getSingleResult();
        return new ModuleEventOperations.Observation((Long) row[0], Optional.ofNullable((Instant) row[1]), (Long) row[2]);
    }

    private void quarantine(EventPublicationAttemptEntity attempt, String code, Instant now) {
        if (attempt.getStatus() == ModuleEventAttemptStatus.QUARANTINED) return;
        ModuleEventImpactBinding binding = binding(attempt);
        attempt.quarantine(code, now);
        if (binding != null) notices.getObject().reserveQuarantined(binding, now);
        afterCommit(() -> operations.getObject().quarantinedAfterCommit());
    }

    private static ModuleEventImpactBinding binding(EventPublicationAttemptEntity attempt) {
        if (attempt.getResolverKey() == null && attempt.getOperationId() == null && attempt.getEpisodeId() == null
                && attempt.getScopeType() == null && attempt.getScopeId() == null && attempt.getOrganizationId() == null) {
            return null;
        }
        // 部分欠損を未接続扱いへ落とさず、Lifecycle側の純scalar検証まで同TXで通す。
        return new ModuleEventImpactBinding(attempt.getResolverKey(), attempt.getOperationId(), attempt.getEpisodeId(),
                attempt.getScopeType(), attempt.getScopeId(), attempt.getOrganizationId());
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private EventPublicationAttemptEntity lock(ModuleEventExecutionFrame frame) {
        // 復旧評価・成功hookとhelper→main→noticeの順を揃える。旧tokenはmainへ進めない。
        EventPublicationAttemptEntity attempt = entityManager.find(EventPublicationAttemptEntity.class,
                frame.attemptId(), LockModeType.PESSIMISTIC_WRITE);
        if (attempt == null || attempt.getStatus() != ModuleEventAttemptStatus.IN_PROGRESS
                || !frame.publicationId().equals(attempt.getPublicationId())
                || !frame.token().equals(attempt.getClaimToken())) return null;
        if (entityManager.createNativeQuery("""
                SELECT id FROM event_publication WHERE id=:id AND completion_date IS NULL
                  AND listener_id=:listener AND event_type=:eventType FOR UPDATE
                """).setParameter("id", bytes(frame.publicationId()))
                .setParameter("listener", frame.listenerId()).setParameter("eventType", frame.event().getClass().getName())
                .getResultList().isEmpty()) return null;
        return attempt;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Claim(TargetEventPublication publication, ModuleEventExecutionFrame frame) { }

    /** public SPIだけで既存UUIDを復元する。Target.ofの新UUIDへ置き換えない。 */
    private static final class RestoredPublication implements TargetEventPublication {
        private final UUID id;
        private final Object event;
        private final PublicationTargetIdentifier target;
        private final Instant publishedAt;
        private Instant completedAt;
        private RestoredPublication(UUID id, Object event, PublicationTargetIdentifier target, Instant publishedAt) {
            this.id = id;
            this.event = event;
            this.target = target;
            this.publishedAt = publishedAt;
        }
        @Override public UUID getIdentifier() { return id; }
        @Override public Object getEvent() { return event; }
        @Override public PublicationTargetIdentifier getTargetIdentifier() { return target; }
        @Override public Instant getPublicationDate() { return publishedAt; }
        @Override public Optional<Instant> getCompletionDate() { return Optional.ofNullable(completedAt); }
        @Override public void markCompleted(Instant instant) { completedAt = instant; }
    }
}
