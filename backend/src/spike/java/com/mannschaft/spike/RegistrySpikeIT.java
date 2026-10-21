package com.mannschaft.spike;

import com.mannschaft.app.auth.event.UserRegisteredEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 保存対象限定・非TX非同期完了の実MySQL試験。保存限定実装より先に作る。 */
@SpringBootTest(classes = RegistrySpikeIT.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RegistrySpikeIT {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("modulith_spike").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));
    static { MYSQL.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", MYSQL::getJdbcUrl);
        values.add("spring.datasource.username", MYSQL::getUsername);
        values.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired Listeners listeners;

    @BeforeEach
    void 清掃する() { jdbc.update("delete from event_publication"); listeners.observed.clear(); listeners.threads.clear(); listeners.failed.set(false); }

    @Test
    void 既存認証イベントは購読されても保存0件() {
        publish(new UserRegisteredEvent(1L, "spike@example.invalid", "検体", "synthetic-secret"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(listeners.observed).containsKey("auth"));
        assertThat(jdbc.queryForObject("select count(*) from event_publication", Long.class)).isZero();
    }

    @Test
    void ReliableEventの対象購読だけ保存し非TX非同期の正常終了で完了する() {
        publish(new SuccessEvent(1L, 2L, Instant.now()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is not null", Long.class)).isEqualTo(1L);
            assertThat(listeners.observed).containsEntry("success", false);
            assertThat(listeners.threads.get("success")).startsWith("module-spike-");
            assertThat(listeners.observed).containsKey("ordinary");
        });
        assertThat(jdbc.queryForList("select listener_id from event_publication", String.class))
                .containsExactly("module-success");
    }

    @Test
    void 非TX非同期の例外は未完了のまま残る() {
        publish(new FailureEvent(1L, 2L, Instant.now()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(listeners.failed.get()).isTrue());
        assertThat(listeners.observed).containsEntry("failure", false);
        assertThat(listeners.threads.get("failure")).startsWith("module-spike-");
        assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null", Long.class)).isEqualTo(1L);
    }

    @Test
    void 公式Entityの実MySQLスキーマと索引を保存する() throws Exception {
        var columns = jdbc.queryForList("show columns from event_publication");
        assertThat(columns.stream().map(column -> column.get("Field").toString()))
                .containsExactlyInAnyOrder("id", "publication_date", "listener_id", "serialized_event", "event_type", "completion_date");
        String ddl = jdbc.queryForMap("show create table event_publication").get("Create Table").toString();
        String indexes = jdbc.queryForList("show index from event_publication").toString();
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("spike.output") + "-schema.txt"),
                ddl + "\n\n" + columns + "\n\n" + indexes);
    }

    void publish(Object event) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> publisher.publishEvent(event));
    }

    /** Registry登録対象の印。スパイク内だけで使う。 */
    interface ReliableEvent { }
    /** 正常終了検体。 */
    record SuccessEvent(Long userId, Long organizationId, Instant occurredAt) implements ReliableEvent { }
    /** 例外検体。 */
    record FailureEvent(Long userId, Long organizationId, Instant occurredAt) implements ReliableEvent { }

    /** 注釈と対象IDの両条件を検証する購読Bean。 */
    static class Listeners {
        final Map<String, Boolean> observed = new ConcurrentHashMap<>();
        final Map<String, String> threads = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        @Async("module-event-pool")
        @TransactionalEventListener(id = "legacy-auth")
        public void auth(UserRegisteredEvent event) { observed.put("auth", TransactionSynchronizationManager.isActualTransactionActive()); }
        @ModuleEventListener(id = "module-success")
        public void success(SuccessEvent event) { threads.put("success", Thread.currentThread().getName()); observed.put("success", TransactionSynchronizationManager.isActualTransactionActive()); }
        @Async("module-event-pool")
        @TransactionalEventListener(id = "ordinary-reliable")
        public void ordinary(SuccessEvent event) { observed.put("ordinary", TransactionSynchronizationManager.isActualTransactionActive()); }
        @ModuleEventListener(id = "module-failure")
        public void failure(FailureEvent event) {
            threads.put("failure", Thread.currentThread().getName());
            observed.put("failure", TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("予定された検体例外");
        }
    }

    /** Registry自動構成だけを起動し、本番全体のcomponent scanを避ける検体。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableAsync
    static class App {
        @Bean Listeners listeners() { return new Listeners(); }
        @Bean org.springframework.scheduling.annotation.AsyncConfigurer asyncConfigurer(Listeners listeners) {
            return new org.springframework.scheduling.annotation.AsyncConfigurer() {
                @Override public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                    return (exception, method, arguments) -> {
                        if (!method.getName().equals("failure")) { throw new IllegalStateException("予定外の非同期失敗", exception); }
                        listeners.failed.set(true);
                    };
                }
            };
        }
        @Bean("module-event-pool") Executor executor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(20);
            executor.setThreadNamePrefix("module-spike-"); executor.initialize();
            return executor;
        }
    }
}