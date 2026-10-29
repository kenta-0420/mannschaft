package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.config.ShedLockConfig;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M07/M09。二つの独立AOP Context/LockProviderが同じ実DB lockを競う。
 * 既存test profileに従い自動schedulingは有効化しない。明示pollだけを実行する。
 * 公開再配信seamを通さないstubは同期assertで検出し、待機timeoutを赤の成功と呼ばない。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("同一DBで所有再配信検体と所有ShedLockテーブルを使う")
class ModuleEventReplayBatchIT extends AbstractMySqlIntegrationTest {
    @Autowired private IncompleteEventPublications replay;
    @Autowired private ModuleEventOperations operations;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EventPublicationRepository publications;
    @Autowired private ModuleEventReplayTestFixture.Listeners listeners;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> publicationIds = new ArrayList<>();
    private final List<UUID> attemptIds = new ArrayList<>();
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private String table;
    private JdbcTemplate jdbc;
    private UUID target;
    private ModuleEventReplayTestFixture.Probe probe;

    @BeforeEach
    void 所有テーブルと購読を用意する() {
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class)).isZero();
        table = "m_replay_lock_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (name VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL)");
        target = UUID.randomUUID();
        probe = listeners.register(target);
        probe.fail = false;
    }

    @AfterEach
    void 所有資産だけを解放する() {
        try {
            contexts.forEach(AnnotationConfigApplicationContext::close);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteAllById(attemptIds);
                entityManager.flush();
                publications.deletePublications(publicationIds);
            });
        } finally {
            if (target != null) listeners.remove(target);
            if (table != null) jdbc.execute("DROP TABLE " + table);
        }
    }

    @Test
    void 毎分バッチは既存管理入口と明示lockと背景処理規約を持つ() throws Exception {
        var method = ModuleEventReplayBatch.class.getMethod("poll");
        assertThat(method.getParameterCount()).isZero();
        assertThat(method.getAnnotation(BatchEndpoint.class).name()).isEqualTo(ModuleEventReplayBatch.BATCH_NAME);
        assertThat(method.getAnnotation(BackgroundFeaturePolicy.class).mode()).isEqualTo(BackgroundFeatureMode.ALWAYS);
        assertThat(method.getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(60_000);
        var lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo(ModuleEventReplayBatch.LOCK_NAME);
        // この猶予は初期候補であり、M18の負荷検証による確定を代替しない。
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT3M");
    }

    @Test
    void 空でも実再配信入口を一回通り業務を呼ばない() {
        batch(replayProbe(null, null)).poll();
        assertThat(calls).hasValue(1);
        assertThat(probe.invocations).hasValue(0);
    }

    @Test
    void 一件の通常再投入で実listenerが完了し永続行が消える() {
        seed();
        batch(replayProbe(null, null)).poll();
        assertThat(calls).as("未接続stubを待機に進めない").hasValue(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(probe.finished).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class)).isZero();
        });
    }

    @Test
    void 独立二Providerの同時呼出は同じDBlockで一方だけ実再投入する() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> returned = new CompletableFuture<>();
        IncompleteEventPublications delegate = replayProbe(entered, release);
        var first = batch(delegate);
        var second = batch(delegate);
        var worker = Executors.newSingleThreadExecutor();
        var running = worker.submit(() -> {
            try { first.poll(); } finally { returned.complete(null); }
        });
        try {
            // stubならpollが直ちに戻る。どちらも起きなければ環境timeoutでありassertionredではない。
            CompletableFuture.anyOf(entered, returned).get(10, TimeUnit.SECONDS);
            assertThat(calls).as("実再投入seamへ到達したことを同期確認").hasValue(1);
            assertThat(entered).isCompleted();
            second.poll();
            assertThat(calls).as("別Context/Providerも同じlock名でskipする").hasValue(1);
            assertThat(running).isNotDone();
        } finally {
            release.countDown();
            try { running.get(20, TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private ModuleEventReplayBatch batch(IncompleteEventPublications delegate) {
        LockProvider provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName(table).usingDbTime().build());
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.register(LockFixtures.class);
        context.registerBean(LockProvider.class, () -> provider);
        context.registerBean(ModuleEventReplayBatch.class, () -> new ModuleEventReplayBatch(delegate, operations));
        context.refresh();
        return context.getBean(ModuleEventReplayBatch.class);
    }

    private IncompleteEventPublications replayProbe(CompletableFuture<Void> entered, CountDownLatch release) {
        ProxyFactory proxy = new ProxyFactory(replay);
        proxy.setInterfaces(IncompleteEventPublications.class);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().startsWith("resubmitIncompletePublications")) {
                calls.incrementAndGet();
                if (entered != null) entered.complete(null);
                if (release != null && !release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("所有再投入ラッチの解放timeout");
                }
            }
            return invocation.proceed();
        });
        return (IncompleteEventPublications) proxy.getProxy();
    }

    private void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var event = new ModuleEventReplayTestFixture.ReplayEvent(target, null, LocalDateTime.now());
            var publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of(ModuleEventReplayTestFixture.LISTENER_ID), Instant.now().minusSeconds(3600)));
            publicationIds.add(publication.getIdentifier());
            var attempt = EventPublicationAttemptEntity.builder().publicationId(publication.getIdentifier())
                    .claimToken(UUID.randomUUID()).status(ModuleEventAttemptStatus.PENDING).retryCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            attemptIds.add(new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                    .saveAndFlush(attempt).getId());
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT3M", order = ShedLockConfig.ADVISOR_ORDER)
    private static class LockFixtures { }
}
