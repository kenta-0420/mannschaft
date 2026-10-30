package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.EventPublicationAttemptEntity;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 未実行の実 DB ロック順試練。Store 本体を変えず、実 apply の notice 先行取得を観測する。
 * claimRecovery は先に独立 TX で commit、TX 外評価後に別 TX で apply する。
 * 将来 coordinator もこの分離が必須。同 TX claim+apply では claim の notice lock が残り順序修正にならない。
 * completion worker は将来 hook と同じ helper 保持→intent→成功削除の検体であり、core hook 接続の合格ではない。
 * 観測は origin/main MarketFinalizeNotificationTransactionIT:351 と BirthProfileLockWaitObserver の
 * 自己 MYSQL/getJdbcUrl/getPassword・root 別接続の先例を再利用する。既存 observer は package-private のため輸入しない。
 * Testcontainers MySQLContainer.configure は MYSQL_ROOT_PASSWORD に getPassword と同じ値を設定する。
 * 自己コンテナだけに read-only SELECT し、GRANT/設定変更/他 DB/資格情報出力は行わない。
 * 権限・SQL・待機 timeout・実 deadlock は OTHER/未合格。観測済み逆順だけを assertion red とする。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeLockOrderIT extends AbstractMySqlIntegrationTest {
    private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
    private final List<UUID> ownNoticeIds = new ArrayList<>();
    private final List<UUID> ownPublications = new ArrayList<>();
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 全worker終了後に自分の検体だけを掃除する() {
        for (UUID id : ownPublications) {
            jdbc.update("DELETE FROM event_publication_attempts WHERE publication_id = ?", bytes(id));
            jdbc.update("DELETE FROM event_publication WHERE id = ?", bytes(id));
        }
        if (!ownNoticeIds.isEmpty()) inNewTx(() -> entityManager.createQuery(
                "delete from ModuleEventNoticeOutboxEntity n where n.id in :ids")
                .setParameter("ids", ownNoticeIds).executeUpdate());
    }

    @Test
    void completionのhelper待機中に復旧applyがnoticeを先取りせずintentと削除が同時commitされる() throws Exception {
        Fixture fixture = createFixture();
        // claim の TX を閉じてから業務評価へ進む。実業務 resolver の close 根拠は別の採用 AC。
        List<ModuleEventNoticeOutboxEntity> claims = inNewTx(() -> store().claimRecovery(100, FUTURE));
        if (claims.size() != 1 || !claims.getFirst().getId().equals(fixture.recovery().getId())) {
            throw new IllegalStateException("自己復旧予約の独立 claim 前提を満たさない");
        }
        UUID token = claims.getFirst().getClaimToken();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("業務評価の前に claim TX が終了していない");
        }
        ModuleEventRecoveryState evaluatedOutsideTransaction = ModuleEventRecoveryState.CLOSED_COMPLETE;
        CompletableFuture<Long> helperLocked = new CompletableFuture<>();
        CompletableFuture<Long> recoveryConnection = new CompletableFuture<>();
        CompletableFuture<Boolean> allowCompletion = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> completion = null;
        Future<Boolean> recovery = null;
        try (Connection observer = openOwnObserver()) {
            // 観測権限の欠落を guard assertion に偽装せず、worker 開始前に SQLException として検出する。
            preflight(observer);
            completion = executor.submit(() -> inNewTx(() -> {
                EventPublicationAttemptEntity helper = entityManager.find(EventPublicationAttemptEntity.class,
                        fixture.attemptId(), LockModeType.PESSIMISTIC_WRITE);
                if (helper == null) throw new IllegalStateException("自己 helper が存在しない");
                helperLocked.complete(connectionId());
                if (!decision(allowCompletion)) {
                    // 旧順序の検出時は意図的に rollback。逆順 cycle 自体を起こさず実 guard の失敗を残す。
                    throw new CompletionRollback();
                }
                store().reserveRecoveryIntent(fixture.recovery());
                publications.markCompleted(fixture.publicationId(), FUTURE);
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).delete(helper);
                return true;
            }));
            long completionId = helperLocked.get(15, TimeUnit.SECONDS);
            recovery = executor.submit(() -> inNewTx(() -> {
                recoveryConnection.complete(connectionId());
                return store().applyRecoveryEvaluation(fixture.recovery().getId(), token,
                        evaluatedOutsideTransaction, FUTURE.plusSeconds(60), FUTURE);
            }));
            long recoveryId = recoveryConnection.get(15, TimeUnit.SECONDS);
            Future<Boolean> observedWorker = recovery;
            await().pollInSameThread().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(10))
                    .until(() -> {
                        if (observedWorker.isDone()) {
                            observedWorker.get(); // SQL/deadlock 等は OTHER のまま伝播する。
                            throw new IllegalStateException("実 helper 待機より前に復旧 worker が終了した");
                        }
                        return helperWaits(observer, completionId, recoveryId) > 0;
                    });
            boolean noticeTakenBeforeHelper = grantedNoticeLocks(observer, recoveryId) > 0;
            allowCompletion.complete(!noticeTakenBeforeHelper);
            boolean completionCommitted = completed(completion);
            boolean promoted = recovery.get(60, TimeUnit.SECONDS);
            // worker の SQL failure/timeout を先に回収してから、観測済み逆順だけを実 assertion red にする。
            assertThat(noticeTakenBeforeHelper).as("helper を待つ復旧 TX が先に notice 行を保持してはならない")
                    .isFalse();
            assertThat(completionCommitted).isTrue();
            assertThat(promoted).isTrue();
            inNewTx(() -> {
                ModuleEventNoticeOutboxEntity recovered = new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class,
                        entityManager).findById(fixture.recovery().getId()).orElseThrow();
                assertThat(recovered.getStatus()).isEqualTo("PENDING");
                assertThat(recovered.getClaimToken()).isNull();
                assertThat(recovered.getNoticeKey()).isEqualTo(fixture.recovery().getNoticeKey());
                assertThat(recovered.getNoticeRef()).isEqualTo(fixture.recovery().getNoticeRef());
                assertThat(entityManager.find(EventPublicationAttemptEntity.class, fixture.attemptId())).isNull();
                return null;
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE id = ?", Long.class,
                    bytes(fixture.publicationId()))).isZero();
        } finally {
            allowCompletion.complete(false);
            executor.shutdown();
            // guard/観測失敗でも helper を解放し、両 TX を join した後だけ @AfterEach へ進む。
            try {
                if (completion != null) completed(completion);
                if (recovery != null) recovery.get(60, TimeUnit.SECONDS);
            } finally {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    throw new IllegalStateException("自己 worker が終了せず cleanup 前提を満たさない");
                }
            }
        }
    }

    private Fixture createFixture() {
        return inNewTx(() -> {
            LocalDateTime future = LocalDateTime.ofInstant(FUTURE, SERVER_ZONE);
            TargetEventPublication publication = publications.create(TargetEventPublication.of(
                    new LockOrderFixtureEvent(UUID.randomUUID(), null, future),
                    PublicationTargetIdentifier.of("module-notice-lock-it-" + UUID.randomUUID()), FUTURE));
            ownPublications.add(publication.getIdentifier());
            UUID operation = UUID.randomUUID();
            UUID episode = UUID.randomUUID();
            EventPublicationAttemptEntity helper = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class,
                    entityManager).saveAndFlush(EventPublicationAttemptEntity.builder().id(UuidV7.generate())
                    .publicationId(publication.getIdentifier()).retryCount(0).status(ModuleEventAttemptStatus.IN_PROGRESS)
                    .claimToken(UUID.randomUUID()).lastAttemptAt(FUTURE).leaseUntil(FUTURE.plusSeconds(300))
                    .resolverKey("notice-lock-it").operationId(operation).episodeId(episode)
                    .scopeType(ModuleEventNoticeScope.TEAM).scopeId(8201L).organizationId(null)
                    .createdAt(FUTURE).updatedAt(FUTURE).version(0).build());
            ModuleEventNoticeOutboxEntity impact = draft(operation, episode, "DELAYED", "PENDING");
            store().reserve(impact);
            ModuleEventNoticeOutboxEntity recovery = store().reserveRecoveryIntent(
                    draft(operation, episode, "RECOVERED", "WAITING_RECOVERY")).orElseThrow();
            return new Fixture(publication.getIdentifier(), helper.getId(), recovery);
        });
    }

    private ModuleEventNoticeOutboxEntity draft(UUID operation, UUID episode, String phase, String status) {
        UUID id = UuidV7.generate();
        ownNoticeIds.add(id);
        return ModuleEventNoticeOutboxEntity.builder().id(id).noticeKey(UUID.randomUUID()).resolverKey("notice-lock-it")
                .operationId(operation).episodeId(episode).scopeType("TEAM").scopeId(8201L).organizationId(null)
                .phase(phase).audience("CURRENT_ADMINS").descriptorVersion(1).status(status).attemptCount(0)
                .nextAttemptAt(FUTURE).createdAt(FUTURE).updatedAt(FUTURE).build();
    }

    private Connection openOwnObserver() throws SQLException {
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?")
                + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true";
        Connection observer = DriverManager.getConnection(url, "root", MYSQL.getPassword());
        try {
            observer.setReadOnly(true);
            return observer;
        } catch (SQLException failure) {
            observer.close();
            throw failure;
        }
    }

    private static void preflight(Connection observer) throws SQLException {
        for (String table : List.of("data_locks", "data_lock_waits", "threads")) {
            try (PreparedStatement query = observer.prepareStatement("SELECT COUNT(*) FROM performance_schema." + table
                    + " WHERE 1 = 0")) {
                query.setQueryTimeout(1);
                try (ResultSet ignored = query.executeQuery()) { /* 権限だけを確認し値を取得しない。 */ }
            }
        }
    }

    private long helperWaits(Connection observer, long blocker, long waiter) throws SQLException {
        try (PreparedStatement query = observer.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested ON requested.ENGINE = waits.ENGINE
                  AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads waiting ON waiting.THREAD_ID = waits.REQUESTING_THREAD_ID
                JOIN performance_schema.threads blocking ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = 'event_publication_attempts'
                  AND requested.INDEX_NAME = 'PRIMARY' AND requested.LOCK_TYPE = 'RECORD'
                  AND waiting.PROCESSLIST_ID = ? AND blocking.PROCESSLIST_ID = ?
                """)) {
            query.setQueryTimeout(1);
            query.setString(1, MYSQL.getDatabaseName());
            query.setLong(2, waiter);
            query.setLong(3, blocker);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private long grantedNoticeLocks(Connection observer, long connectionId) throws SQLException {
        try (PreparedStatement query = observer.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_locks held
                JOIN performance_schema.threads owning ON owning.THREAD_ID = held.THREAD_ID
                WHERE held.OBJECT_SCHEMA = ? AND held.OBJECT_NAME = 'module_event_notice_outbox'
                  AND held.INDEX_NAME = 'PRIMARY' AND held.LOCK_TYPE = 'RECORD' AND held.LOCK_STATUS = 'GRANTED'
                  AND owning.PROCESSLIST_ID = ?
                """)) {
            query.setQueryTimeout(1);
            query.setString(1, MYSQL.getDatabaseName());
            query.setLong(2, connectionId);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private long connectionId() { return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); }
    private ModuleEventNoticeOutboxStore store() { return new ModuleEventNoticeOutboxStore(entityManager); }

    private <T> T inNewTx(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> action.get());
    }

    private static boolean decision(CompletableFuture<Boolean> signal) {
        try { return signal.get(45, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException("completion の制御待機が未完了", failure); }
    }

    private static boolean completed(Future<Boolean> worker) throws Exception {
        try { return worker.get(60, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof CompletionRollback) return false;
            throw failure;
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private record Fixture(UUID publicationId, UUID attemptId, ModuleEventNoticeOutboxEntity recovery) { }
    record LockOrderFixtureEvent(UUID fixtureId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }
    private static final class CompletionRollback extends RuntimeException { }
}
