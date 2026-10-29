package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactResolverRegistry;
import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M08/M10/M11の実MySQL境界を既存実装へ追加照合する回帰防止テスト。
 * 同じDBへの子ApplicationContext再生成で永続状態を検査し、Javaプロセス再起動の証拠にはしない。
 * 初回greenは現契約の確認であり、人工的なredを作らない。所有ID以外の行を削除しない。
 */
@ModuleEventMySqlTest
@TestPropertySource(properties = "spring.modulith.moments.enabled=false")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("同じDBの再配信候補を他の試験と並走させない")
class ModuleEventReplayBoundaryIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Duration CUTOFF = Duration.ofMinutes(5);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String LISTENER = "module-replay-boundary-it";
    private static final int CAPACITY = 200;
    @Autowired private ApplicationContext parent;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;
    @Autowired @Qualifier("module-event-pool") private ModuleEventTaskExecutor parentPool;
    private long parentTasksBefore;
    private int parentCapacityBefore;
    private final List<UUID> ownPublications = new ArrayList<>();
    private final List<UUID> ownAttempts = new ArrayList<>();
    private final List<UUID> ownAudits = new ArrayList<>();
    private BoundaryFixture fixture;

    @BeforeEach
    void 他者の候補が無いことを確認して独立Contextを作る() {
        assertNoForeignRows();
        var pool = parentPool.getThreadPoolExecutor();
        await().atMost(WAIT).until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                && pool.getTaskCount() == pool.getCompletedTaskCount());
        parentTasksBefore = pool.getTaskCount();
        parentCapacityBefore = parentPool.availableCapacity();
        fixture = start(NOW);
    }

    @AfterEach
    void 自分のContextと所有行だけを解放する() {
        try {
            if (fixture != null) fixture.close();
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                new SimpleJpaRepository<>(ModuleEventResumeAuditEntity.class, entityManager).deleteAllById(ownAudits);
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteAllById(ownAttempts);
                entityManager.flush();
                publications.deletePublications(ownPublications);
            });
            assertNoForeignRows();
            assertThat(parentPool.getThreadPoolExecutor().getTaskCount()).as("親poolへの漏出ゼロ")
                    .isEqualTo(parentTasksBefore);
            assertThat(parentPool.availableCapacity()).isEqualTo(parentCapacityBefore);
        }
    }

    @Test
    void M08_cutoff前と等号をclaimし一マイクロ秒後は変更しない() {
        Instant cutoff = NOW.minus(CUTOFF);
        var before = seed(cutoff.minusNanos(1000), 0);
        var equal = seed(cutoff, 0);
        var after = seed(cutoff.plusNanos(1000), 0);
        var unchanged = snapshot(after);

        var claims = fixture.store().claim(cutoff, NOW, 100);

        assertThat(claims).extracting(claim -> claim.frame().publicationId())
                .containsExactlyInAnyOrder(before.publicationId(), equal.publicationId());
        assertThat(snapshot(after)).isEqualTo(unchanged);
        assertThat(attempt(before).getRetryCount()).isZero();
        assertThat(attempt(equal).getRetryCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 100})
    void M08_公式再配信は実空き容量の予算分だけ百一候補を実購読へ受理する(int budget) {
        List<ModuleEventExecutionFrame> seeded = new ArrayList<>();
        for (int index = 0; index < 101; index++) seeded.add(seed(NOW.minusSeconds(3600).plusNanos(index * 1000L), 0));
        List<AttemptSnapshot> unchanged = seeded.stream().map(this::snapshot).toList();
        List<ModuleEventTaskExecutor.Batch> held = new ArrayList<>();
        var pool = fixture.pool();
        // 通常用50slotを残す製品契約に合わせ、物理freeを50/51/150へ揃える。
        int desiredFree = 50 + budget;
        try {
            while (pool.availableCapacity() > desiredFree) {
                held.add(pool.reserveReplayBudget(Math.min(100, pool.availableCapacity() - desiredFree), 0));
            }
            assertThat(pool.availableCapacity()).isEqualTo(desiredFree);
            fixture.replay().resubmitIncompletePublicationsOlderThan(CUTOFF);
            fixture.idle();

            assertThat(fixture.calls().get()).isEqualTo(budget);
            assertThat(seeded.stream().filter(frame -> attempt(frame).getRetryCount() == 1)).hasSize(budget);
            for (int index = 0; index < seeded.size(); index++) {
                var row = attempt(seeded.get(index));
                assertThat(row.getRetryCount()).isEqualTo(index < budget ? 1 : 0);
                assertThat(row.getStatus()).isEqualTo(ModuleEventAttemptStatus.PENDING);
                if (index >= budget) assertThat(snapshot(seeded.get(index))).isEqualTo(unchanged.get(index));
            }
            assertThat(pool.availableCapacity()).isEqualTo(desiredFree);
        } finally {
            held.forEach(ModuleEventTaskExecutor.Batch::close);
        }
        assertThat(pool.availableCapacity()).isEqualTo(CAPACITY);
    }

    @Test
    void M10_二スレッドの実proxyclaimは同じ一候補を合計一回だけ所有する() throws Exception {
        var source = seed(NOW.minusSeconds(3600), 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<List<ModuleEventPublicationStore.Claim>> claim = () -> {
                ready.countDown();
                if (!release.await(WAIT.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("claim開始待機timeout");
                return fixture.store().claim(NOW.minus(CUTOFF), NOW, 1);
            };
            var first = threads.submit(claim);
            var second = threads.submit(claim);
            try {
                assertThat(ready.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            List<ModuleEventPublicationStore.Claim> actual = new ArrayList<>(first.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            actual.addAll(second.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertThat(actual).hasSize(1);
            assertThat(actual.getFirst().frame().publicationId()).isEqualTo(source.publicationId());
            assertThat(attempt(source).getClaimToken()).isEqualTo(actual.getFirst().frame().token());
            assertThat(attempt(source).getRetryCount()).isZero();
        }
    }

    @Test
    void M10_旧世代の全callbackは現在世代と別行と監査を変更しない() {
        var old = seed(NOW.minusSeconds(3600), 0);
        UUID auditId = seedAudit(old.publicationId());
        var auditBefore = auditSnapshot(auditId);
        var sentinel = seed(NOW.plusSeconds(3600), 0);
        var sentinelBefore = snapshot(sentinel);
        var first = fixture.store().claim(NOW.minus(CUTOFF), NOW, 1).getFirst().frame();
        Instant recoveredAt = NOW.plusSeconds(301);
        fixture.clock().now = recoveredAt;
        var current = fixture.store().claim(recoveredAt.minus(CUTOFF), recoveredAt, 1).getFirst().frame();
        assertThat(current.token()).isNotEqualTo(first.token());
        var currentBefore = snapshot(current);
        long auditCountBefore = auditCount();
        double acceptedBefore = fixture.meters().counter(ModuleEventOperations.REPLAY_ACCEPTED).count();

        for (var stale : List.of(old, first)) {
            assertThat(fixture.store().admit(stale)).isFalse();
            fixture.store().finish(stale, true);
            fixture.store().finish(stale, false);
            fixture.store().rejected(stale);
        }

        assertThat(snapshot(current)).isEqualTo(currentBefore);
        assertThat(snapshot(sentinel)).isEqualTo(sentinelBefore);
        assertThat(auditCount()).isEqualTo(auditCountBefore);
        assertThat(auditSnapshot(auditId)).isEqualTo(auditBefore);
        assertThat(fixture.meters().counter(ModuleEventOperations.REPLAY_ACCEPTED).count()).isEqualTo(acceptedBefore);
        assertThat(fixture.calls()).hasValue(0);
        assertThat(fixture.store().admit(current)).isTrue();
        fixture.store().finish(current, false);
        assertThat(attempt(current).getRetryCount()).isEqualTo(1);
        assertThat(attempt(current).getStatus()).isEqualTo(ModuleEventAttemptStatus.PENDING);
    }

    @Test
    void M11_十回目失敗の隔離はContext再生成後も時刻理由回数を維持して再配信ゼロ() {
        var source = seed(NOW.minusSeconds(3600), 9);
        fixture.replay().resubmitIncompletePublicationsOlderThan(CUTOFF);
        fixture.idle();
        assertThat(fixture.calls()).hasValue(1);
        var persisted = snapshot(source);
        assertQuarantined(source, "LISTENER_FAILURE");
        var previous = fixture;
        previous.close();
        fixture = start(NOW.plusSeconds(3600));
        assertIndependent(previous, fixture);

        fixture.replay().resubmitIncompletePublicationsOlderThan(CUTOFF);
        fixture.idle();

        assertThat(fixture.calls()).hasValue(0);
        assertThat(snapshot(source)).isEqualTo(persisted);
    }

    @Test
    void M11_十回目admit直後停止は新Contextのlease期限等号で隔離し十一回目業務ゼロ() {
        var source = seed(NOW.minusSeconds(3600), 9);
        var claimed = fixture.store().claim(NOW.minus(CUTOFF), NOW, 1).getFirst().frame();
        assertThat(fixture.store().admit(claimed)).isTrue();
        assertThat(attempt(source).getRetryCount()).isEqualTo(10);
        assertThat(fixture.calls()).hasValue(0);
        Instant leaseExpiry = attempt(source).getLeaseUntil().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
        // 業務・finishを呼ばずContextを破棄し、DBに受理commitだけが残った停止点を再現する。
        var previous = fixture;
        previous.close();
        fixture = start(leaseExpiry);
        assertIndependent(previous, fixture);

        fixture.replay().resubmitIncompletePublicationsOlderThan(CUTOFF);
        fixture.idle();

        assertThat(fixture.calls()).hasValue(0);
        assertQuarantined(source, "RETRY_LIMIT");
        var persisted = snapshot(source);
        fixture.clock().now = leaseExpiry.plusSeconds(3600);
        fixture.replay().resubmitIncompletePublicationsOlderThan(CUTOFF);
        fixture.idle();
        assertThat(snapshot(source)).isEqualTo(persisted);
        assertThat(fixture.calls()).hasValue(0);
    }

    private void assertQuarantined(ModuleEventExecutionFrame frame, String reason) {
        var row = attempt(frame);
        assertThat(row.getStatus()).isEqualTo(ModuleEventAttemptStatus.QUARANTINED);
        assertThat(row.getRetryCount()).isEqualTo(10);
        assertThat(row.getQuarantinedAt()).isNotNull();
        assertThat(row.getFailureCode()).isEqualTo(reason);
    }

    private static void assertIndependent(BoundaryFixture before, BoundaryFixture after) {
        assertThat(before.context().isActive()).isFalse();
        assertThat(after.store()).isNotSameAs(before.store());
        assertThat(after.registry()).isNotSameAs(before.registry());
        assertThat(after.frames()).isNotSameAs(before.frames());
        assertThat(after.pool()).isNotSameAs(before.pool());
        assertThat(after.catalog()).isNotSameAs(before.catalog());
    }

    private ModuleEventExecutionFrame seed(Instant publishedAt, int retries) {
        return new TransactionTemplate(transactions).execute(status -> {
            var event = new BoundaryEvent(UUID.randomUUID(), null, local(NOW));
            var publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of(LISTENER), publishedAt));
            ownPublications.add(publication.getIdentifier());
            UUID token = UUID.randomUUID();
            var row = EventPublicationAttemptEntity.builder().publicationId(publication.getIdentifier())
                    .claimToken(token).status(ModuleEventAttemptStatus.PENDING).retryCount(retries)
                    .createdAt(local(NOW)).updatedAt(local(NOW)).build();
            row = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).saveAndFlush(row);
            ownAttempts.add(row.getId());
            return new ModuleEventExecutionFrame(event, publication.getIdentifier(), row.getId(), token, LISTENER, false);
        });
    }

    private EventPublicationAttemptEntity attempt(ModuleEventExecutionFrame frame) {
        return new TransactionTemplate(transactions).execute(status -> {
            entityManager.clear();
            return new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                    .findById(frame.attemptId()).orElseThrow();
        });
    }

    private AttemptSnapshot snapshot(ModuleEventExecutionFrame frame) {
        var row = attempt(frame);
        boolean incomplete = new TransactionTemplate(transactions).execute(status -> entityManager
                .createNativeQuery("SELECT id FROM event_publication WHERE id = :id AND completion_date IS NULL")
                .setParameter("id", bytes(frame.publicationId())).getResultList().size() == 1);
        return new AttemptSnapshot(row.getStatus(), row.getClaimToken(), row.getRetryCount(), row.getLeaseUntil(),
                row.getVersion(), row.getLastAttemptAt(), row.getQuarantinedAt(), row.getFailureCode(),
                row.getCreatedAt(), row.getUpdatedAt(), incomplete);
    }

    /** 履歴の不変性だけを検査する自己行であり、actorの本人認可を実証する検体ではない。 */
    private UUID seedAudit(UUID publicationId) {
        return new TransactionTemplate(transactions).execute(status -> {
            var audit = ModuleEventResumeAuditEntity.builder().publicationId(publicationId)
                    .actorUserId(1L).reason("旧世代callbackの履歴保護検体").createdAt(local(NOW)).build();
            audit = new SimpleJpaRepository<>(ModuleEventResumeAuditEntity.class, entityManager).saveAndFlush(audit);
            ownAudits.add(audit.getId());
            return audit.getId();
        });
    }

    private AuditSnapshot auditSnapshot(UUID id) {
        return new TransactionTemplate(transactions).execute(status -> {
            entityManager.clear();
            var audit = new SimpleJpaRepository<>(ModuleEventResumeAuditEntity.class, entityManager)
                    .findById(id).orElseThrow();
            return new AuditSnapshot(audit.getId(), audit.getPublicationId(), audit.getActorUserId(),
                    audit.getReason(), audit.getCreatedAt());
        });
    }

    private long auditCount() {
        return new TransactionTemplate(transactions).execute(status -> entityManager.createQuery(
                "select count(a) from ModuleEventResumeAuditEntity a", Long.class).getSingleResult());
    }

    private void assertNoForeignRows() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(entityManager.createQuery("select count(a) from EventPublicationAttemptEntity a", Long.class)
                    .getSingleResult()).as("他者の補助行を黙って再配信・削除しない").isZero();
            assertThat(((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM event_publication")
                    .getSingleResult()).longValue()).as("他者の本表を黙って再配信・削除しない").isZero();
        });
    }

    private BoundaryFixture start(Instant now) {
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setParentBeanFactory(parent.getAutowireCapableBeanFactory());
        context.register(Scope.Configuration.class);
        context.registerBean(EntityManager.class, () -> entityManager, definition -> definition.setPrimary(true));
        context.registerBean(MutableClock.class, () -> new MutableClock(now), definition -> definition.setPrimary(true));
        context.registerBean(SimpleMeterRegistry.class, definition -> definition.setPrimary(true));
        context.registerBean(ModuleEventListenerCatalog.class, definition -> definition.setPrimary(true));
        context.registerBean(ModuleEventPublicationStore.class, definition -> definition.setPrimary(true));
        context.registerBean(ModuleEventOperations.class, definition -> definition.setPrimary(true));
        context.registerBean(ModuleEventImpactResolverRegistry.class, () -> new ModuleEventImpactResolverRegistry(List.of()), definition -> definition.setPrimary(true));
        context.registerBean(BoundaryListener.class);
        try {
            context.refresh();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(
                    context.getBean(ModuleEventPublicationStore.class))).as("実TX proxyが必要").isTrue();
            return new BoundaryFixture(context, context.getBean(ModuleEventPublicationStore.class),
                    context.getBean(RestrictedEventPublicationRegistry.class),
                    context.getBean(ModuleEventExecutionFrame.Context.class),
                    context.getBean(ModuleEventTaskExecutor.class), context.getBean(ModuleEventListenerCatalog.class),
                    context.getBean(MutableClock.class), context.getBean(SimpleMeterRegistry.class),
                    context.getBean(BoundaryListener.class).calls());
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    private static byte[] bytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** JPA再読取の前後で監査対象となる全永続状態を比較する。 */
    private record AttemptSnapshot(ModuleEventAttemptStatus status, UUID token, int count, LocalDateTime lease,
            Long version, LocalDateTime lastAttemptAt, LocalDateTime quarantinedAt, String reason,
            LocalDateTime createdAt, LocalDateTime updatedAt, boolean incomplete) { }

    /** 保存済み監査行の全fieldを旧callbackの前後で比較する。 */
    private record AuditSnapshot(UUID id, UUID publicationId, Long actorUserId, String reason, LocalDateTime createdAt) { }

    /** 同一DBを利用しつつ、再起動で捨てるMの全インスタンスを保持する。 */
    private record BoundaryFixture(AnnotationConfigApplicationContext context, ModuleEventPublicationStore store,
            RestrictedEventPublicationRegistry registry, ModuleEventExecutionFrame.Context frames,
            ModuleEventTaskExecutor pool, ModuleEventListenerCatalog catalog, MutableClock clock,
            SimpleMeterRegistry meters, AtomicInteger calls) implements AutoCloseable {
        IncompleteEventPublications replay() { return context.getBean("applicationEventMulticaster", IncompleteEventPublications.class); }
        void idle() {
            var actual = pool.getThreadPoolExecutor();
            await().atMost(WAIT).until(() -> actual.getActiveCount() == 0 && actual.getQueue().isEmpty()
                    && actual.getTaskCount() == actual.getCompletedTaskCount());
        }
        @Override public void close() {
            if (!context.isActive()) return;
            idle();
            assertThat(pool.availableCapacity()).isEqualTo(CAPACITY);
            context.close();
        }
    }

    /** 製品Clockを改変せず、明示した瞬間のDB境界だけを再現する。 */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    /** 子Context専用の実配送検体。payloadは一意IDとscalarだけにする。 */
    public record BoundaryEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }

    /** 実Async/Completion advisorを通る失敗購読で試行数と隔離を観測する。 */
    public static class BoundaryListener {
        private final AtomicInteger calls = new AtomicInteger();
        public AtomicInteger calls() { return calls; }
        @ModuleEventListener(id = LISTENER)
        public void receive(BoundaryEvent event) {
            calls.incrementAndGet();
            throw new IllegalStateException("境界試験の意図した購読失敗");
        }
    }

    /** 親のテスト走査に自動取込させず、子に明示登録する設定。 */
    private static final class Scope {
        private Scope() { }

        /** 子専用の実Async/TX/公式Multicasterを構成する。 */
        @TestConfiguration(proxyBeanMethods = false)
        @EnableAsync(proxyTargetClass = true)
        @EnableAspectJAutoProxy(proxyTargetClass = true)
        @EnableTransactionManagement(proxyTargetClass = true)
        static class Configuration {
            @Bean(name = "moduleEventExecutionContext")
            static ModuleEventExecutionFrame.Context boundaryFrames() {
                return new ModuleEventExecutionFrame.Context();
            }
            @Bean static ModuleEventInvocationCapturePostProcessor boundaryCapture(ModuleEventExecutionFrame.Context frames) {
                return new ModuleEventInvocationCapturePostProcessor(frames);
            }
            @Bean(name = "moduleEventRegistry")
            @Primary
            RestrictedEventPublicationRegistry boundaryRegistry(EventPublicationRepository repository, Clock clock,
                    ModuleEventListenerCatalog catalog, ObjectProvider<ModuleEventImpactResolverRegistry> impacts,
                    ModuleEventExecutionFrame.Context frames, ModuleEventPublicationStore store,
                    ObjectProvider<ModuleEventTaskExecutor> executor) {
                return new RestrictedEventPublicationRegistry(repository, clock, catalog, impacts, frames, store, executor);
            }
            @Bean(name = "applicationEventMulticaster")
            static PersistentApplicationEventMulticaster boundaryMulticaster(ObjectProvider<EventPublicationRegistry> registry,
                    org.springframework.core.env.Environment environment) {
                return new PersistentApplicationEventMulticaster(registry::getObject, () -> environment);
            }
            @Bean(name = "completionRegisteringAdvisor")
            static CompletionRegisteringAdvisor boundaryCompletion(ObjectProvider<EventPublicationRegistry> registry) {
                return new CompletionRegisteringAdvisor(registry::getObject);
            }
            @Bean(name = "module-event-pool")
            @Primary
            ModuleEventTaskExecutor boundaryPool(ModuleEventExecutionFrame.Context frames,
                    ObjectProvider<RestrictedEventPublicationRegistry> registry) {
                var pool = new ModuleEventTaskExecutor(frames, new ModuleEventExecutionFrame.Owner() {
                    @Override public boolean admit(ModuleEventExecutionFrame frame) { return registry.getObject().admit(frame); }
                    @Override public void rejected(ModuleEventExecutionFrame frame) { registry.getObject().rejected(frame); }
                    @Override public void finished(ModuleEventExecutionFrame frame) { registry.getObject().finished(frame); }
                }, CAPACITY, task -> task);
                pool.setCorePoolSize(4);
                pool.setMaxPoolSize(4);
                pool.setQueueCapacity(CAPACITY);
                pool.setThreadNamePrefix("module-boundary-");
                pool.setWaitForTasksToCompleteOnShutdown(true);
                pool.setAwaitTerminationSeconds((int) WAIT.toSeconds());
                return pool;
            }
        }
    }
}
