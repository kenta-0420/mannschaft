package com.mannschaft.app.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventExecutionAppTestFixture.Control;
import com.mannschaft.app.moduleevent.ModuleEventExecutionAppTestFixture.Event;
import com.mannschaft.app.moduleevent.ModuleEventExecutionAppTestFixture.Fault;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** mini保証を実App・実publisherへ接続する試練。未接続操作は実assertion redとして残す。 */
@ModuleEventMySqlTest
@Import(ModuleEventExecutionAppTestFixture.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventExecutionAppIT extends AbstractMySqlIntegrationTest {
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ModuleEventExecutionFrame.Context frames;
    @Autowired private ModuleEventPublicationStore store;
    @Autowired private RestrictedEventPublicationRegistry registry;
    @Autowired @Qualifier("module-event-pool") private ModuleEventTaskExecutor executor;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @Autowired private Control control;
    @Autowired private ModuleEventExecutionAppTestFixture.ReadOnlyCaller readOnlyCaller;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 自分のUUIDだけをRepository経由で掃除する() {
        control.fault = Fault.NONE;
        control.committedBodies.forEach((operation, expected) -> await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(control.bodies.getOrDefault(operation,
                        new java.util.concurrent.atomic.AtomicInteger())).hasValue(expected)));
        List<UUID> ids = List.copyOf(control.publications);
        if (!ids.isEmpty()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                List<EventPublicationAttemptEntity> attempts = entityManager.createQuery(
                        "select a from EventPublicationAttemptEntity a where a.publicationId in :ids",
                        EventPublicationAttemptEntity.class).setParameter("ids", ids).getResultList();
                new SimpleJpaRepository<EventPublicationAttemptEntity, UUID>(
                        EventPublicationAttemptEntity.class, entityManager).deleteAll(attempts);
                entityManager.flush();
                publications.deletePublications(ids);
            });
            control.publications.clear();
        }
        control.committedBodies.clear();
        control.bodies.clear();
    }

    @Test
    void 二件目の公式保存失敗後も同実体を次TXで発行できる() {
        failedPublicationThenRetry(Fault.PUBLICATION_SECOND);
    }

    @Test
    void 二件目の補助保存失敗後も同実体を次TXで発行できる() {
        failedPublicationThenRetry(Fault.HELPER_SECOND);
    }

    @Test
    void 実flush制約失敗後も同実体を次TXで発行できる() {
        Event event = event();
        control.arm(event, Fault.DEFERRED_FLUSH);
        assertThatThrownBy(() -> publish(event)).isInstanceOf(RuntimeException.class);
        // beforeCommitの任意throwをflush失敗と取り違えない。
        assertThat(control.flushEntered).isTrue();
        assertThat(control.flushFailure.get()).isNotNull();
        assertThat(hasConstraintCause(control.flushFailure.get())).isTrue();
        retryAndAwait(event);
    }

    @Test
    void 古いrollbackとfinishedは次TXのFrameを解除しない() throws Exception {
        Event event = event();
        control.arm(event, Fault.NONE);
        CountDownLatch rollbackEntered = new CountDownLatch(1);
        CountDownLatch releaseRollback = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread publisherThread = new Thread(() -> {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public int getOrder() { return Integer.MIN_VALUE; }
                        @Override public void afterCompletion(int completion) {
                            rollbackEntered.countDown();
                            waitFor(releaseRollback);
                        }
                    });
                    publisher.publishEvent(event);
                    status.setRollbackOnly();
                });
            } catch (Throwable failure) { threadFailure.set(failure); }
        }, "execution-app-it-rollback");
        publisherThread.start();
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            assertThat(rollbackEntered.await(20, TimeUnit.SECONDS)).isTrue();
            ModuleEventExecutionFrame old = frames.capture(event, ModuleEventExecutionAppTestFixture.FIRST)
                    .orElse(null);
            assertThat(old).isNotNull();
            registry.finished(old);
            // 同じ実体の別対象も旧世代を明示解放してから次発行へ進む。
            frames.capture(event, ModuleEventExecutionAppTestFixture.SECOND).ifPresent(registry::finished);
            CountDownLatch workersEntered = new CountDownLatch(4);
            for (int index = 0; index < 4; index++) executor.execute(() -> {
                workersEntered.countDown();
                waitFor(releaseWorkers);
            });
            assertThat(workersEntered.await(20, TimeUnit.SECONDS)).isTrue();
            publish(event);
            releaseRollback.countDown();
            publisherThread.join(20000);
            assertThat(publisherThread.isAlive()).isFalse();
            assertThat(threadFailure.get()).isNull();
            registry.finished(old);
            assertThatThrownBy(() -> frames.assertUnregistered(event,
                    List.of(ModuleEventExecutionAppTestFixture.FIRST)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            releaseRollback.countDown();
            releaseWorkers.countDown();
            publisherThread.join(20000);
        }
    }

    @Test
    void SQL失敗時は先に確保した予算を全て返す() {
        int before = executor.availableCapacity();
        control.fault = Fault.CLAIM_SQL;
        assertThatThrownBy(() -> {
            try (var budget = executor.reserveReplayBudget(100, 50)) {
                assertThat(budget.size()).isGreaterThan(0);
                assertThat(executor.availableCapacity()).isEqualTo(before - budget.size());
                store.claim(Instant.now().minusSeconds(300), Instant.now(), budget.size());
            }
        }).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(executor.availableCapacity()).isEqualTo(before);
    }

    @Test
    void 既存所有のある対象は全対象preflightで拒否する() {
        Event event = event();
        ModuleEventExecutionFrame frame = frame(event);
        frames.register(frame);
        try {
            assertThatThrownBy(() -> frames.assertUnregistered(event,
                    List.of(ModuleEventExecutionAppTestFixture.FIRST, ModuleEventExecutionAppTestFixture.SECOND)))
                    .isInstanceOf(IllegalStateException.class);
        } finally { frames.release(frame, () -> { }); }
    }

    @Test
    void readonly業務TXからの対象発行は公式保存前に拒否する() {
        Event event = event();
        control.arm(event, Fault.NONE);
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> {
            readOnly.executeWithoutResult(status -> publisher.publishEvent(event));
            control.committedBodies.merge(event.operationId(), 2, Integer::sum);
        }).isInstanceOf(IllegalStateException.class);
        assertThat(control.publicationCalls).hasValue(0);
    }

    @Test
    void publisher内で新Storeの補助保存を同一TXに接続する() {
        Event event = event();
        control.arm(event, Fault.NONE);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            assertThat(control.helperCalls).hasValue(2);
            status.setRollbackOnly();
        });
    }

    @Test
    void readonly呼出元からも短TXで現tokenの再配信を受理する() {
        Event event = event();
        UUID token = UUID.randomUUID();
        AtomicReference<ModuleEventExecutionFrame> frame = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            TargetEventPublication publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of(ModuleEventExecutionAppTestFixture.FIRST),
                    Instant.now().minusSeconds(300)));
            Instant now = Instant.now();
            EventPublicationAttemptEntity attempt = new SimpleJpaRepository<EventPublicationAttemptEntity, UUID>(
                    EventPublicationAttemptEntity.class, entityManager).saveAndFlush(
                    EventPublicationAttemptEntity.builder().publicationId(publication.getIdentifier())
                            .retryCount(0).status(ModuleEventAttemptStatus.IN_PROGRESS).claimToken(token)
                            .leaseUntil(now.plusSeconds(300)).createdAt(now).updatedAt(now).build());
            frame.set(new ModuleEventExecutionFrame(event, publication.getIdentifier(), attempt.getId(), token,
                    ModuleEventExecutionAppTestFixture.FIRST, false));
        });
        assertThat(readOnlyCaller.admit(frame.get())).isTrue();
        Integer retries = new TransactionTemplate(transactions).execute(status -> entityManager.find(
                EventPublicationAttemptEntity.class, frame.get().attemptId()).getRetryCount());
        assertThat(retries).isEqualTo(1);
    }

    private void failedPublicationThenRetry(Fault fault) {
        Event event = event();
        control.arm(event, fault);
        assertThatThrownBy(() -> publish(event)).isInstanceOf(RuntimeException.class);
        assertThat(control.bodies).doesNotContainKey(event.operationId());
        retryAndAwait(event);
    }

    private void retryAndAwait(Event event) {
        control.arm(event, Fault.NONE);
        publish(event);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                control.bodies.getOrDefault(event.operationId(), new java.util.concurrent.atomic.AtomicInteger()))
                .hasValue(2));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Long remaining = new TransactionTemplate(transactions).execute(status -> entityManager.createQuery(
                    "select count(a) from EventPublicationAttemptEntity a where a.publicationId in :ids", Long.class)
                    .setParameter("ids", List.copyOf(control.publications)).getSingleResult());
            assertThat(remaining).isZero();
        });
    }

    private void publish(Event event) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> publisher.publishEvent(event));
        control.committedBodies.merge(event.operationId(), 2, Integer::sum);
    }
    private static Event event() { return new Event(UUID.randomUUID(), UUID.randomUUID(), LocalDateTime.now()); }
    private static ModuleEventExecutionFrame frame(Event event) {
        return new ModuleEventExecutionFrame(event, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ModuleEventExecutionAppTestFixture.FIRST, true);
    }
    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("自己検体の待機期限です");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
    private static boolean hasConstraintCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.PropertyValueException
                    || cause instanceof org.hibernate.exception.ConstraintViolationException
                    || cause instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
        }
        return false;
    }
}
