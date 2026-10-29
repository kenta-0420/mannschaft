package com.mannschaft.app.moduleevent;

import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M01〜M06: 実 Bean・実 MySQL・明示 commit で保存境界を検査する。
 * AFTER_COMMIT が必ず観測されるよう、テスト全体を rollback TX で包まない。
 * 本クラスの schema 検査は JPA 起動互換性であり、Flyway 全再生の代替ではない。
 */
@Import(ModuleEventRegistryIT.Fixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventRegistryIT extends AbstractMySqlIntegrationTest {
    @PersistenceContext private EntityManager entityManager;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Listeners listeners;
    private Probe probe;
    @Autowired private List<EventPublicationRegistry> registries;
    private UUID ownedTarget;

    @BeforeEach
    void 検体を初期化する() {
        probe = listeners.observations();
        probe.failureObserved.set(false);
        probe.ordinaryObserved.set(false);
        probe.txObserved.set(null);
        probe.threadObserved.set(null);
        probe.authStarted = new CountDownLatch(1);
        probe.authRelease = new CountDownLatch(1);
        probe.authFinished = new CountDownLatch(1);
        probe.authToken = null;
    }

    @AfterEach
    void 自分の検体だけを掃除する() {
        probe.authRelease.countDown();
        if (ownedTarget != null) {
            cleanup(ownedTarget);
        }
    }

    @Test
    void M01_Registryは一つで公式本表は六列を持つ() {
        assertThat(registries).hasSize(1);
        assertThat(registries.getFirst()).isInstanceOf(RestrictedEventPublicationRegistry.class);
        assertThat(jdbc.queryForList("SHOW COLUMNS FROM event_publication").stream()
                .map(column -> column.get("Field")))
                .containsExactlyInAnyOrder("id", "publication_date", "completion_date",
                        "listener_id", "event_type", "serialized_event");
    }

    @Test
    void M02_Reliableの対象購読だけを保存する() {
        UUID target = UUID.randomUUID();
        ownedTarget = target;
        FailureEvent event = new FailureEvent(target, 7L, LocalDateTime.now());
        publish(event);
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(probe.failureObserved).isTrue();
                assertThat(probe.ordinaryObserved).isTrue();
            });
            assertThat(jdbc.queryForList(
                    "SELECT listener_id FROM event_publication WHERE serialized_event LIKE ?",
                    String.class, "%" + target + "%"))
                    .containsExactly("module-registry-it-failure");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication_attempts a JOIN event_publication p "
                            + "ON p.id = a.publication_id WHERE p.serialized_event LIKE ? "
                            + "AND p.listener_id = ?", Long.class, "%" + target + "%",
                    "module-registry-it-failure")).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication_attempts a JOIN event_publication p "
                            + "ON p.id = a.publication_id WHERE p.serialized_event LIKE ? "
                            + "AND p.listener_id = ?", Long.class, "%" + target + "%",
                    "module-registry-it-ordinary")).isZero();
        } finally {
            cleanup(target);
        }
    }

    @Test
    void M03_認証イベントは実commitと購読実行後も両表に保存しない() throws Exception {
        String token = "registry-it-" + UUID.randomUUID();
        probe.authToken = token;
        Long attemptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class);
        try {
            publish(new UserRegisteredEvent(919191L, "registry-it@example.invalid", "検体", token));
            assertThat(probe.authStarted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE listener_id = ? "
                            + "AND serialized_event LIKE ?", Long.class,
                    "module-registry-it-legacy-auth", "%" + token + "%")).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?", Long.class,
                    "%" + token + "%")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class))
                    .isEqualTo(attemptsBefore);
        } finally {
            probe.authRelease.countDown();
            if (probe.authStarted.getCount() == 0 && !probe.authFinished.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("自分の認証購読検体が終了しなかった");
            }
            // 一意な token に一致する自分の検体だけを掃除する。
            jdbc.update("DELETE a FROM event_publication_attempts a JOIN event_publication p "
                    + "ON p.id = a.publication_id WHERE p.serialized_event LIKE ?", "%" + token + "%");
            jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", "%" + token + "%");
            probe.authToken = null;
        }
    }

    @Test
    void M04_非同期購読の正常完了で本表と補助行を削除する() {
        UUID target = UUID.randomUUID();
        ownedTarget = target;
        byte[] publicationId = new TransactionTemplate(transactions).execute(status -> {
            publisher.publishEvent(new SuccessEvent(target, 7L, LocalDateTime.now()));
            entityManager.flush();
            return jdbc.queryForObject("SELECT id FROM event_publication WHERE serialized_event LIKE ?",
                    byte[].class, "%" + target + "%");
        });
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(probe.threadObserved.get()).startsWith("module-event-");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts "
                    + "WHERE publication_id = ?", Long.class, publicationId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?", Long.class,
                    "%" + target + "%")).isZero();
        });
    }

    @Test
    void M05_発行TXの中で補助行を作りrollbackで両表とも消える() {
        UUID target = UUID.randomUUID();
        ownedTarget = target;
        byte[] publicationId = new TransactionTemplate(transactions).execute(status -> {
            publisher.publishEvent(new FailureEvent(target, 7L, LocalDateTime.now()));
            entityManager.flush();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication_attempts a JOIN event_publication p "
                            + "ON p.id = a.publication_id WHERE p.serialized_event LIKE ?", Long.class,
                    "%" + target + "%")).isEqualTo(1L);
            byte[] id = jdbc.queryForObject("SELECT id FROM event_publication WHERE serialized_event LIKE ?",
                    byte[].class, "%" + target + "%");
            status.setRollbackOnly();
            return id;
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?", Long.class,
                "%" + target + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts "
                + "WHERE publication_id = ?", Long.class, publicationId)).isZero();
        assertThat(probe.failureObserved).isFalse();
    }

    @Test
    void M06_対象購読は専用executor上でTXを持たない() {
        UUID target = UUID.randomUUID();
        ownedTarget = target;
        publish(new SuccessEvent(target, 7L, LocalDateTime.now()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(probe.threadObserved.get()).startsWith("module-event-");
            assertThat(probe.txObserved.get()).isFalse();
        });
    }

    private void publish(Object event) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private void cleanup(UUID target) {
        jdbc.update("DELETE a FROM event_publication_attempts a JOIN event_publication p "
                + "ON p.id = a.publication_id WHERE p.serialized_event LIKE ?", "%" + target + "%");
        jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", "%" + target + "%");
    }

    /** 検体は ID・scope・発生日時だけを持つ。 */
    record FailureEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }
    /** 正常終了用の検体。 */
    record SuccessEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }

    /** 本番の組立に検体 listener だけを足す。DB・自分の Bean はモックしない。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean Listeners moduleRegistryTestListeners() { return new Listeners(); }
    }

    /** AOP proxy 本体のfieldを直接読まず、実targetと共有する観測状態。 */
    static class Probe {
        final AtomicBoolean failureObserved = new AtomicBoolean();
        final AtomicBoolean ordinaryObserved = new AtomicBoolean();
        final AtomicReference<Boolean> txObserved = new AtomicReference<>();
        final AtomicReference<String> threadObserved = new AtomicReference<>();
        CountDownLatch authStarted = new CountDownLatch(1);
        CountDownLatch authRelease = new CountDownLatch(1);
        CountDownLatch authFinished = new CountDownLatch(1);
        volatile String authToken;
    }

    /** 非同期の実行と保存をそれぞれ観測する購読検体。 */
    static class Listeners {
        private final Probe probe = new Probe();

        public Probe observations() { return probe; }

        @ModuleEventListener(id = "module-registry-it-failure")
        public void failure(FailureEvent event) {
            probe.failureObserved.set(true);
            throw new IllegalStateException("意図した購読失敗の検体");
        }

        @Async("module-event-pool")
        @TransactionalEventListener(id = "module-registry-it-ordinary")
        public void ordinary(FailureEvent event) {
            probe.ordinaryObserved.set(true);
            throw new IllegalStateException("保存対象外の通常購読検体");
        }

        @Async("module-event-pool")
        @TransactionalEventListener(id = "module-registry-it-legacy-auth")
        public void auth(UserRegisteredEvent event) throws InterruptedException {
            if (probe.authToken == null || !probe.authToken.equals(event.getRawToken())) {
                return;
            }
            CountDownLatch release = probe.authRelease;
            CountDownLatch finished = probe.authFinished;
            probe.authStarted.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("認証購読の検体を解放しなかった");
                }
            } finally {
                finished.countDown();
            }
        }

        @ModuleEventListener(id = "module-registry-it-success")
        public void success(SuccessEvent event) {
            probe.txObserved.set(TransactionSynchronizationManager.isActualTransactionActive());
            probe.threadObserved.set(Thread.currentThread().getName());
        }
    }
}
