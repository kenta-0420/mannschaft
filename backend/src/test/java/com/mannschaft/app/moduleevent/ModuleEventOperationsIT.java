package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.config.ReplicaRoutingAspect;
import com.mannschaft.app.moduleevent.api.ModuleEventOperationsApi;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent.Kind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M07/M12の運用観測。実DB・実Store・実routing/TX AOPを保持し、警報だけを局所Contextで観測する。
 * BeanFactoryだけを親へ結び、ApplicationContextの親は設定しない。固定kindの親errorreport配送と
 * 集約cacheを汚染しない副作用分離であり、既存M12実配送ITの代替ではない。
 * test profileは既存SchedulingConfig/ShedLockConfigを無効化するため、自動tickは発生しない。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("所有publicationだけを使い、同一DBの再配信を並走させない")
class ModuleEventOperationsIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final String TARGET = ModuleEventReplayTestFixture.LISTENER_ID;
    @Autowired private ApplicationContext parent;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> ownPublications = new ArrayList<>();
    private final List<UUID> ownAttempts = new ArrayList<>();
    private final List<Kind> alerts = new CopyOnWriteArrayList<>();
    private final List<Boolean> alertTransactions = new CopyOnWriteArrayList<>();
    private AnnotationConfigApplicationContext local;
    private ModuleEventPublicationStore store;
    private ModuleEventOperations operations;
    private SimpleMeterRegistry meters;
    private boolean rollBackStore;

    @BeforeEach
    void 局所運用Contextを組む() {
        Long count = new TransactionTemplate(transactions).execute(status -> entityManager.createQuery(
                "select count(a) from EventPublicationAttemptEntity a", Long.class).getSingleResult());
        assertThat(count).as("他者の未完了を観測しない実行前提").isZero();
        Number mainCount = new TransactionTemplate(transactions).execute(status -> (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM event_publication").getSingleResult());
        assertThat(mainCount.longValue()).isZero();
        local = new AnnotationConfigApplicationContext();
        local.getBeanFactory().setParentBeanFactory(parent.getAutowireCapableBeanFactory());
        local.register(Fixtures.class);
        local.registerBean(EntityManager.class, () -> entityManager);
        local.registerBean(Clock.class, Clock::systemUTC);
        local.registerBean(SimpleMeterRegistry.class);
        local.registerBean(ModuleEventPublicationStore.class);
        local.registerBean(ModuleEventOperations.class);
        local.registerBean(ModuleEventOperationsApi.class);
        local.registerBean(ReadOnlyCaller.class);
        local.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof ModuleEventOperationalAlertEvent signal) {
                alerts.add(signal.kind());
                alertTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            }
        });
        local.refresh();
        store = local.getBean(ModuleEventPublicationStore.class);
        operations = local.getBean(ModuleEventOperations.class);
        meters = local.getBean(SimpleMeterRegistry.class);
        // 公開Advisedを使い、実TXの内部でだけsynthetic操作のrollbackを指示する。
        afterTransaction(store, invocation -> {
            Object result = invocation.proceed();
            if (rollBackStore && (invocation.getMethod().getName().equals("claim")
                    || invocation.getMethod().getName().equals("finish"))) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return result;
        });
    }

    @AfterEach
    void 自分の行と局所Contextを解放する() {
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteAllById(ownAttempts);
                entityManager.flush();
                publications.deletePublications(ownPublications);
            });
        } finally {
            if (local != null) local.close();
            DataSourceContextHolder.clear();
        }
    }

    @Test
    void 五観測は固定名で登録されIDラベルを持たない() {
        operations.observe(NOW);
        for (String name : List.of(ModuleEventOperations.INCOMPLETE, ModuleEventOperations.OLDEST_AGE,
                ModuleEventOperations.REPLAY_ACCEPTED, ModuleEventOperations.QUARANTINED,
                ModuleEventOperations.PATTERN_F_FAILURE)) {
            var meter = meters.find(name).meter();
            assertThat(meter).as(name).isNotNull();
            assertThat(meter.getId().getTags()).isEmpty();
        }
    }

    @Test
    void 公開F失敗一報は一件で一般購読失敗はFに数えない() {
        double before = meters.counter(ModuleEventOperations.PATTERN_F_FAILURE).count();
        var ordinary = seed(false, 0, ModuleEventAttemptStatus.IN_PROGRESS, Instant.now().plusSeconds(60), NOW);
        store.finish(ordinary.frame(), false);
        assertThat(meters.counter(ModuleEventOperations.PATTERN_F_FAILURE).count()).isEqualTo(before);
        local.getBean(ModuleEventOperationsApi.class).recordPatternFCompletionFailure();
        assertThat(meters.counter(ModuleEventOperations.PATTERN_F_FAILURE).count()).isEqualTo(before + 1);
        assertThat(ModuleEventOperationsApi.class.getAnnotationsByType(Transactional.class)).isEmpty();
    }

    @Test
    void 再投入受理commit報告だけを一件数える() {
        double before = meters.counter(ModuleEventOperations.REPLAY_ACCEPTED).count();
        operations.recordReplayAccepted();
        assertThat(meters.counter(ModuleEventOperations.REPLAY_ACCEPTED).count()).isEqualTo(before + 1);
    }

    @Test
    void 空のsnapshotは件数ゼロで最古なし() {
        var snapshot = operations.observe(NOW);
        assertThat(snapshot.incompleteCount()).isZero();
        assertThat(snapshot.quarantinedCount()).isZero();
        assertThat(snapshot.oldestIncomplete()).isEmpty();
    }

    @Test
    void snapshotは全未完了状態と隔離件数と最古を返す() {
        seed(false, 0, ModuleEventAttemptStatus.PENDING, null, NOW.minusSeconds(120));
        seed(false, 1, ModuleEventAttemptStatus.IN_PROGRESS, NOW.plusSeconds(60), NOW.minusSeconds(240));
        seed(false, 10, ModuleEventAttemptStatus.QUARANTINED, null, NOW.minusSeconds(60));
        var snapshot = operations.observe(NOW);
        assertThat(snapshot.incompleteCount()).isEqualTo(3);
        assertThat(snapshot.quarantinedCount()).isEqualTo(1);
        assertThat(snapshot.oldestIncomplete()).contains(NOW.minusSeconds(240));
        assertThat(meters.find(ModuleEventOperations.INCOMPLETE).gauge()).isNotNull();
        assertThat(meters.get(ModuleEventOperations.INCOMPLETE).gauge().value()).isEqualTo(3);
        assertThat(meters.get(ModuleEventOperations.QUARANTINED).gauge().value()).isEqualTo(1);
        assertThat(meters.get(ModuleEventOperations.OLDEST_AGE).gauge().value()).isEqualTo(240);
    }

    @Test
    void readOnly呼出元のTXを運用中だけ停止しPRIMARYを選び復元する() {
        List<Boolean> active = new ArrayList<>();
        List<DataSourceType> routes = new ArrayList<>();
        afterTransaction(operations, invocation -> {
            active.add(TransactionSynchronizationManager.isActualTransactionActive());
            routes.add(DataSourceContextHolder.getDataSourceType());
            return invocation.proceed();
        });
        local.getBean(ReadOnlyCaller.class).call();
        assertThat(active).containsExactly(false);
        assertThat(routes).containsExactly(DataSourceType.PRIMARY);
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {1800, 1801})
    void 未完了最古の三十分ちょうどは警報ゼロで超過だけ一件(long age) {
        seed(false, 0, ModuleEventAttemptStatus.IN_PROGRESS, NOW.plusSeconds(60), NOW.minusSeconds(age));
        operations.monitor(NOW);
        assertThat(alerts).hasSize(age == 1800 ? 0 : 1);
        if (age > 1800) assertThat(alerts).containsExactly(Kind.OLDEST_PENDING_30_MIN);
        assertThat(alertTransactions).doesNotContain(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"poison", "expired-tenth", "failed-tenth"})
    void 隔離三入口はrollback時ゼロでcommit後一回だけ警報する(String entry) {
        boolean failed = entry.equals("failed-tenth");
        var fixture = seed(entry.equals("poison"), failed ? 9 : entry.equals("expired-tenth") ? 10 : 0,
                failed || entry.equals("expired-tenth") ? ModuleEventAttemptStatus.IN_PROGRESS : ModuleEventAttemptStatus.PENDING,
                failed ? Instant.now().plusSeconds(60) : entry.equals("expired-tenth") ? NOW.minusSeconds(1) : null,
                NOW.minusSeconds(3600));
        if (failed) assertThat(store.admit(fixture.frame())).isTrue();
        rollBackStore = true;
        quarantine(entry, fixture);
        assertThat(alerts).isEmpty();
        assertThat(attempt(fixture).getStatus()).isNotEqualTo(ModuleEventAttemptStatus.QUARANTINED);
        rollBackStore = false;
        quarantine(entry, fixture);
        assertThat(attempt(fixture).getStatus()).isEqualTo(ModuleEventAttemptStatus.QUARANTINED);
        assertThat(alerts).containsExactly(Kind.QUARANTINED);
        assertThat(alertTransactions).containsExactly(false);
        quarantine(entry, fixture);
        assertThat(alerts).as("隔離済みの再走査や旧callbackから重複しない").hasSize(1);
    }

    @Test
    void afterCommit残留TXでも運用proxyは停止し呼出元へ復元する() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        operations.quarantinedAfterCommit();
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    }
                }));
        assertThat(alerts).containsExactly(Kind.QUARANTINED);
        assertThat(alertTransactions).containsExactly(false);
    }

    private void quarantine(String entry, Fixture fixture) {
        if (entry.equals("failed-tenth")) store.finish(fixture.frame(), false);
        else store.claim(NOW.minus(Duration.ofMinutes(5)), NOW, 100);
    }

    private EventPublicationAttemptEntity attempt(Fixture fixture) {
        return new TransactionTemplate(transactions).execute(status ->
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                        .findById(fixture.frame().attemptId()).orElseThrow());
    }

    private Fixture seed(boolean poison, int retries, ModuleEventAttemptStatus state, Instant lease, Instant published) {
        return new TransactionTemplate(transactions).execute(status -> {
            Object event = poison ? new UnknownEvent(UUID.randomUUID(), LocalDateTime.now())
                    : new ModuleEventReplayTestFixture.ReplayEvent(UUID.randomUUID(), null, LocalDateTime.now());
            var publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of(TARGET), published));
            ownPublications.add(publication.getIdentifier());
            UUID token = UUID.randomUUID();
            var row = EventPublicationAttemptEntity.builder().publicationId(publication.getIdentifier())
                    .claimToken(token).status(state).retryCount(retries)
                    .leaseUntil(lease == null ? null : LocalDateTime.ofInstant(lease, UserZoneLocalDateTimeParser.SERVER_ZONE))
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            row = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).saveAndFlush(row);
            ownAttempts.add(row.getId());
            return new Fixture(new ModuleEventExecutionFrame(event, publication.getIdentifier(), row.getId(), token, TARGET, false));
        });
    }

    private static void afterTransaction(Object proxy, MethodInterceptor advice) {
        Advised advised = (Advised) proxy;
        for (int i = 0; i < advised.getAdvisors().length; i++) {
            if (advised.getAdvisors()[i].getAdvice() instanceof TransactionInterceptor) {
                advised.addAdvisor(i + 1, new DefaultPointcutAdvisor(advice));
                return;
            }
        }
        throw new AssertionError("実TransactionInterceptorが必要です");
    }

    private record Fixture(ModuleEventExecutionFrame frame) { }
    public record UnknownEvent(UUID id, LocalDateTime occurredAt) implements ReliableEvent { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    private static class Fixtures {
        @Bean ReplicaRoutingAspect actualReplicaRoutingAspect() { return new ReplicaRoutingAspect(); }
    }

    public static class ReadOnlyCaller {
        private final ModuleEventOperations operations;
        public ReadOnlyCaller(ModuleEventOperations operations) { this.operations = operations; }
        @Transactional(readOnly = true)
        public void call() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
            operations.observe(NOW);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
        }
    }
}
