package com.mannschaft.app.moduleevent;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static com.mannschaft.app.moduleevent.ModuleEventLoadTestFixture.bytes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M18の未実行実測。Ops greenとcore固定統合後にのみ、直列実5分を実行する。
 * 1000 backlogとnormal3000件全体を測り、未完了/未供給を母集団から除かない。
 * 実購読は固定正常returnだけ。C/F業務性能やL0-M測定免除の証明ではない。
 */
@ModuleEventMySqlTest
@Import(ModuleEventLoadTestFixture.class)
// Momentsの自動設定による親の全batch起動を防ぎ、M18の子で実schedulerだけを明示起動する。
@TestPropertySource(properties = "spring.modulith.moments.enabled=false")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModuleEventLoadIT extends AbstractMySqlIntegrationTest {
    private static final int NORMAL_COUNT = 3000;
    private static final long SLOT_NS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Duration TAIL = Duration.ofSeconds(15);
    @Autowired private ModuleEventLoadTestFixture.Probe probe;
    @Autowired private ConfigurableApplicationContext application;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MeterRegistry meters;
    @Autowired private ModuleEventPublicationStore store;
    @Autowired @Qualifier("module-event-pool") private ModuleEventTaskExecutor pool;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    void backlog1000と毎秒10件を実5分混在させ全normalのcommit後p99と拒否を測る() throws Exception {
        String table = "m18_lock_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> report = new LinkedHashMap<>();
        List<ModuleEventLoadTestFixture.Entry> normal = new ArrayList<>();
        AnnotationConfigApplicationContext scheduler = null;
        ScheduledExecutorService sampler = null;
        ModuleEventLoadTestFixture.StoreObservation observation = null;
        boolean tableCreated = false;
        long start = 0;
        Throwable original = null;
        try {
            assertThat(application.getBeanFactory().getBeanNamesForType(
                    ScheduledAnnotationBeanPostProcessor.class, true, false))
                    .as("親contextでは他domainを含めスケジュールを起動しない").isEmpty();
            assertThat(probe.ticks).as("子schedulerの明示起動前にはtickしない").isEmpty();
            assertThat(pool.getActiveCount()).isZero();
            assertThat(pool.getQueueSize()).isZero();
            assertThat(pool.availableCapacity()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Long.class))
                    .as("他の未完了が存在するDBでは自己負荷の証拠を作らない").isZero();
            assertThat(pool.getCorePoolSize()).isEqualTo(4);
            assertThat(pool.getMaxPoolSize()).isEqualTo(4);
            assertThat(pool.getQueueCapacity()).isEqualTo(200);
            observation = new ModuleEventLoadTestFixture.StoreObservation(store, pool, probe, jdbc);
            ModuleEventLoadTestFixture.Entry warmup = probe.create(ModuleEventLoadTestFixture.Traffic.WARMUP);
            publish(warmup);
            assertThat(await(() -> warmup.finished.get() != 0, Duration.ofSeconds(10))).isTrue();
            assertThat(warmup.failure).isNull();
            assertThat(warmup.publications).hasSize(1);
            assertThat(warmup.finished.get()).isGreaterThanOrEqualTo(warmup.committed.get());
            // frame無し既存submitも同じ容量を解放する。通常3000件の代用には数えない。
            pool.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(pool.submit(() -> 7).get(5, TimeUnit.SECONDS)).isEqualTo(7);
            assertThat(await(this::idle, Duration.ofSeconds(5))).isTrue();
            double acceptedBefore = replayCounter();
            seedBacklog();
            assertThat(remaining()).isEqualTo(1000);
            for (int i = 0; i < NORMAL_COUNT; i++) normal.add(probe.create(ModuleEventLoadTestFixture.Traffic.NORMAL));
            jdbc.execute("CREATE TABLE " + table + " (name VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL)");
            tableCreated = true;
            scheduler = ModuleEventLoadTestFixture.startScheduler(application, probe, jdbc, table);
            assertThat(scheduler.getParent()).isNull();
            assertThat(AopUtils.isAopProxy(scheduler.getBean(ModuleEventReplayBatch.class))).isTrue();
            assertThat(scheduler.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            assertThat(ModuleEventReplayBatch.class.getMethod("poll").getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(60_000L);
            sampler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "m18-pool-sample"));
            sampler.scheduleAtFixedRate(() -> probe.samples.add(new long[] {System.nanoTime(), pool.getActiveCount(),
                    pool.getQueueSize(), pool.availableCapacity()}), 0, 100, TimeUnit.MILLISECONDS);
            start = System.nanoTime();
            report.put("startedAt", Instant.now().toString());
            report.put("originNano", start);
            for (int i = 0; i < normal.size(); i++) normal.get(i).planned = start + i * SLOT_NS;
            for (ModuleEventLoadTestFixture.Entry entry : normal) {
                pace(entry.planned);
                if (System.nanoTime() - entry.planned >= SLOT_NS) {
                    entry.failure = "SUPPLY_SLOT_MISSED";
                    continue; // catch-up burstで供給不足を隠さない。
                }
                try { publish(entry); }
                catch (RuntimeException failure) { entry.failure = failure.getClass().getSimpleName(); }
            }
            pace(start + TimeUnit.SECONDS.toNanos(300));
            report.put("inputWindowElapsedNs", System.nanoTime() - start);
            scheduler.close(); scheduler = null;
            boolean allFinished = await(() -> normal.stream().allMatch(e -> e.finished.get() != 0), TAIL);
            report.put("tailAllFinished", allFinished);
            report.put("tailLimitSeconds", TAIL.toSeconds());
            report.put("finishedAt", Instant.now().toString());
            assertThat(await(this::idle, TAIL)).as("自己poolの終端を待ってからDBを照合する").isTrue();
            long[] latency = normal.stream().mapToLong(e -> e.finished.get() == 0 || e.arrived == 0
                    ? Long.MAX_VALUE : e.finished.get() - e.arrived).sorted().toArray();
            long[] buckets = new long[300];
            for (ModuleEventLoadTestFixture.Entry entry : normal) {
                long bucket = entry.arrived == 0 ? -1 : (entry.arrived - start) / TimeUnit.SECONDS.toNanos(1);
                if (bucket >= 0 && bucket < buckets.length) buckets[(int) bucket]++;
            }
            report.put("actualPerSecond", buckets);
            report.put("normalCompleted", normal.stream().filter(e -> e.finished.get() != 0).count());
            report.put("p50Ns", percentile(latency, 0.50));
            report.put("p95Ns", percentile(latency, 0.95));
            report.put("p99Ns", percentile(latency, 0.99));
            report.put("maxNs", latency[latency.length - 1]);
            report.put("backlogRemaining", remaining());
            report.put("acceptedMeterDelta", replayCounter() - acceptedBefore);
            report.put("lockRows", jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE name = ?", Long.class,
                    ModuleEventReplayBatch.LOCK_NAME));
            assertThat(normal).hasSize(NORMAL_COUNT).allSatisfy(e -> {
                assertThat(e.arrived).isGreaterThanOrEqualTo(e.planned).isLessThan(e.planned + SLOT_NS);
                assertThat(e.failure).isNull();
                assertThat(e.publications).hasSize(1);
                assertThat(e.listenerCalls.get()).isEqualTo(1);
                assertThat(e.finishCalls.get()).isEqualTo(1);
                assertThat(e.finished.get()).isGreaterThanOrEqualTo(e.committed.get()).isPositive();
            });
            assertThat(buckets).containsOnly(10L);
            assertThat(allFinished).isTrue();
            assertThat(percentile(latency, 0.99)).isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(5));
            assertThat(probe.frameRejected.get()).isZero();
            assertThat(probe.physicalRejected.get()).isZero();
            assertThat(probe.maxClaimLimit.get()).isBetween(1, 100);
            assertThat(probe.replayClaimed.get()).isPositive();
            assertThat(probe.replayAdmitted.get()).isPositive();
            assertThat(replayCounter() - acceptedBefore).isEqualTo(probe.replayAdmitted.get());
            assertThat(probe.ticks).hasSizeGreaterThanOrEqualTo(4).allSatisfy(tick -> assertThat(tick[1]).isGreaterThan(tick[0]));
            assertThat(probe.samples).isNotEmpty().allSatisfy(sample -> {
                assertThat(sample[1]).isBetween(0L, 4L);
                assertThat(sample[2]).isBetween(0L, 200L);
                assertThat(sample[3]).isBetween(0L, 200L);
            });
            assertThat(report.get("lockRows")).isEqualTo(1L);
        } catch (Exception | AssertionError failure) {
            original = failure;
            throw failure;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (scheduler != null) scheduler.close();
                if (sampler != null) { sampler.shutdownNow(); assertThat(sampler.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
                pool.getThreadPoolExecutor().shutdown();
                if (!pool.getThreadPoolExecutor().awaitTermination(15, TimeUnit.SECONDS)) {
                    pool.shutdown();
                    assertThat(pool.getThreadPoolExecutor().awaitTermination(15, TimeUnit.SECONDS))
                            .as("自己pool未終端ならobserverもデータも破棄しない").isTrue();
                }
                if (observation != null) observation.close();
                long origin = start;
                report.put("normalPopulation", normal.stream().map(e -> probe.entryResult(e, origin)).toList());
                report.put("ticksStartEndNs", probe.ticks);
                report.put("samplesTimeActiveQueueAvailable", probe.samples);
                report.put("sampleIntervalMs", 100);
                report.put("sampledMaxQueue", probe.samples.stream().mapToLong(s -> s[2]).max().orElse(0));
                report.put("sampledMaxAdmission", probe.samples.stream().mapToLong(s -> 200 - s[3]).max().orElse(0));
                report.put("physicalRejected", probe.physicalRejected.get());
                report.put("frameRejected", probe.frameRejected.get());
                report.put("replayClaimed", probe.replayClaimed.get());
                report.put("replayAdmitted", probe.replayAdmitted.get());
                report.put("capacityAfterStop", pool.availableCapacity());
                report.put("retainedBeforeCleanup", remaining());
                report.put("expectedConfigurationFromFrozenSource", Map.of("normalReserve", 50, "claimLimit", 100,
                        "tickSeconds", 60, "cutoffSeconds", 300, "leaseSeconds", 300));
                System.out.println("M18_RESULT " + mapper.writeValueAsString(report));
                cleanupOwnPublications();
                if (tableCreated) jdbc.execute("DROP TABLE " + table);
            } catch (Exception | AssertionError cleanupFailure) {
                System.out.println("M18_TEARDOWN_FAILURE " + mapper.writeValueAsString(Map.of(
                        "failure", cleanupFailure.getClass().getSimpleName(), "retainedOwnPublicationIds", ownPublications())));
                if (original != null) original.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private void publish(ModuleEventLoadTestFixture.Entry entry) {
        probe.publishing.set(entry);
        try {
            writeTransaction().executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { entry.committed.set(System.nanoTime()); }
                });
                entry.arrived = System.nanoTime();
                application.publishEvent(new ModuleEventLoadTestFixture.LoadEvent(entry.id, null,
                        LocalDateTime.ofInstant(Instant.now(), SERVER_ZONE)));
            });
        } finally { probe.publishing.remove(); }
    }

    private void seedBacklog() {
        writeTransaction().executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), SERVER_ZONE);
            SimpleJpaRepository<EventPublicationAttemptEntity, UUID> attempts = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager);
            for (int i = 0; i < 1000; i++) {
                ModuleEventLoadTestFixture.Entry entry = probe.create(ModuleEventLoadTestFixture.Traffic.BACKLOG);
                TargetEventPublication publication = publications.create(TargetEventPublication.of(
                        new ModuleEventLoadTestFixture.LoadEvent(entry.id, null, now.minusMinutes(10)),
                        PublicationTargetIdentifier.of(ModuleEventLoadTestFixture.LISTENER_ID), Instant.now().minusSeconds(600)));
                entry.publications.add(publication.getIdentifier());
                attempts.save(EventPublicationAttemptEntity.builder().id(UuidV7.generate()).publicationId(publication.getIdentifier())
                        .status(ModuleEventAttemptStatus.PENDING).claimToken(UUID.randomUUID())
                        .retryCount(0).createdAt(now).updatedAt(now).version(0).build());
            }
            entityManager.flush();
        });
    }

    private TransactionTemplate writeTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(false);
        return tx;
    }
    private boolean idle() { return pool.getActiveCount() == 0 && pool.getQueueSize() == 0 && pool.availableCapacity() == 200; }
    private double replayCounter() {
        Counter counter = meters.find(ModuleEventOperations.REPLAY_ACCEPTED).counter();
        return counter == null ? 0 : counter.count();
    }
    private List<UUID> ownPublications() {
        return probe.entries.values().stream().flatMap(e -> e.publications.stream()).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }
    private long remaining() {
        long result = 0;
        List<UUID> ids = ownPublications();
        for (int from = 0; from < ids.size(); from += 100) {
            List<UUID> batch = ids.subList(from, Math.min(from + 100, ids.size()));
            result += jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND id IN ("
                    + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ")", Long.class,
                    batch.stream().map(ModuleEventLoadTestFixture::bytes).toArray());
        }
        return result;
    }
    private void cleanupOwnPublications() {
        List<UUID> ids = ownPublications();
        for (int from = 0; from < ids.size(); from += 100) {
            List<UUID> batch = ids.subList(from, Math.min(from + 100, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            Object[] args = batch.stream().map(ModuleEventLoadTestFixture::bytes).toArray();
            jdbc.update("DELETE FROM event_publication_attempts WHERE publication_id IN (" + placeholders + ")", args);
            jdbc.update("DELETE FROM event_publication WHERE id IN (" + placeholders + ")", args);
        }
    }
    private static long percentile(long[] sorted, double rank) { return sorted[(int) Math.ceil(rank * sorted.length) - 1]; }
    private static boolean await(BooleanSupplier condition, Duration timeout) {
        long until = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        return condition.getAsBoolean();
    }
    private static void pace(long planned) throws InterruptedException {
        while (System.nanoTime() < planned) {
            if (Thread.interrupted()) throw new InterruptedException("M18投入中断");
            LockSupport.parkNanos(Math.min(planned - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }
}
