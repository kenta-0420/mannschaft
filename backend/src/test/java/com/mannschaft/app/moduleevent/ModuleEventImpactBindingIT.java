package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxEntity;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxStore;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 業務識別のsnapshotと復旧pending判定を実TXで結合する。実業務resolverの採用証明ではない。 */
@ModuleEventMySqlTest
@Import(ModuleEventImpactBindingIT.Fixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventImpactBindingIT extends AbstractMySqlIntegrationTest {
    private static final String RESOLVER_KEY = "binding-it-primary";
    private static final long SCOPE_ID = 919211L;
    @PersistenceContext private EntityManager entityManager;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Probe probe;
    private final Set<UUID> operations = new HashSet<>();
    private final Set<UUID> noticeKeys = new HashSet<>();
    private final Map<UUID, Integer> committedExecutions = new HashMap<>();

    @BeforeEach
    void 自分の観測を初期化する() {
        probe.identifyCalls.set(0);
        probe.evaluationCalls.set(0);
        probe.identifyInTransaction.set(false);
        probe.ordinaryObserved.set(false);
    }

    @AfterEach
    void 自分の検体だけ掃除する() {
        for (Map.Entry<UUID, Integer> expected : committedExecutions.entrySet()) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                    probe.executions.getOrDefault(expected.getKey(), new AtomicInteger()).get())
                    .isGreaterThanOrEqualTo(expected.getValue()));
        }
        for (UUID operation : operations) {
            deletePublications(operation);
            probe.executions.remove(operation);
        }
        if (!noticeKeys.isEmpty()) {
            inTx(() -> entityManager.createQuery(
                    "delete from ModuleEventNoticeOutboxEntity n where n.noticeKey in :keys")
                    .setParameter("keys", noticeKeys).executeUpdate());
        }
    }

    @Test
    void 同publisherTXの二購読へ同じbindingを保存しidentifyは一回だけ呼ぶ() {
        BindingEvent event = event(Mode.NORMAL);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            entityManager.flush();
            assertBinding(event);
            assertThat(probe.identifyCalls).hasValue(1);
            assertThat(probe.identifyInTransaction).isTrue();
            assertThat(probe.evaluationCalls).hasValue(0);
        });
        committedExecutions.merge(event.operationId(), 2, Integer::sum);
        awaitPending(event.operationId());
        assertBinding(event);
    }

    @Test
    void rollbackではbindingも本表と補助行も残さない() {
        BindingEvent event = event(Mode.NORMAL);
        AtomicReference<List<byte[]>> ids = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            entityManager.flush();
            assertBinding(event);
            ids.set(publicationIds(event.operationId()));
            status.setRollbackOnly();
        });
        assertNoPublications(event.operationId());
        for (byte[] id : ids.get()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts "
                    + "WHERE publication_id = ?", Long.class, id)).isZero();
        }
        assertThat(probe.executions).doesNotContainKey(event.operationId());
    }

    @Test
    void 不正bindingは先に保存した同TXのイベントもまとめてrollbackする() {
        BindingEvent valid = event(Mode.NORMAL);
        BindingEvent invalid = event(Mode.INVALID);
        AtomicReference<List<byte[]>> ids = new AtomicReference<>(List.of());
        assertThatThrownBy(() -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                publisher.publishEvent(valid);
                entityManager.flush();
                ids.set(publicationIds(valid.operationId()));
                publisher.publishEvent(invalid);
            });
            committedExecutions.merge(valid.operationId(), 2, Integer::sum);
            committedExecutions.merge(invalid.operationId(), 2, Integer::sum);
        }).isInstanceOf(IllegalStateException.class);
        assertNoPublications(valid.operationId());
        assertNoPublications(invalid.operationId());
        assertThat(ids.get()).hasSize(2);
        for (byte[] id : ids.get()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts "
                    + "WHERE publication_id = ?", Long.class, id)).isZero();
        }
        assertThat(probe.executions).doesNotContainKeys(valid.operationId(), invalid.operationId());
    }

    @Test
    void 複数resolverが一致した場合は発行TXを拒否する() {
        BindingEvent event = event(Mode.MULTIPLE);
        assertThatThrownBy(() -> publish(event)).isInstanceOf(IllegalStateException.class);
        assertNoPublications(event.operationId());
        assertThat(probe.executions).doesNotContainKey(event.operationId());
    }

    @Test
    void 未接続イベントは六列nullのまま保存できる() {
        BindingEvent event = event(Mode.EMPTY);
        publish(event);
        awaitPending(event.operationId());
        List<Map<String, Object>> rows = bindingRows(event.operationId());
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.values()).containsOnlyNulls();
        }
        assertThat(probe.identifyCalls).hasValue(1);
        assertThat(probe.evaluationCalls).hasValue(0);
    }

    @Test
    void 明示購読対象がなければidentifyを呼ばず従来購読だけ実行する() {
        UUID operation = UUID.randomUUID();
        operations.add(operation);
        inTx(() -> {
            publisher.publishEvent(new OrdinaryOnlyEvent(operation, LocalDateTime.now()));
            return null;
        });
        assertThat(probe.ordinaryObserved).isTrue();
        assertThat(probe.identifyCalls).hasValue(0);
        assertNoPublications(operation);
    }

    @Test
    void 評価後に同episodeの未完了二購読が増えたら復旧昇格を阻止する() {
        BindingEvent original = event(Mode.NORMAL);
        publish(original);
        awaitPending(original.operationId());
        deletePublications(original.operationId());
        Instant now = Instant.now();
        ModuleEventNoticeOutboxEntity waiting = waiting(original, now);
        List<ModuleEventNoticeOutboxEntity> claimed = inTx(() -> store().claimRecovery(1, now));
        assertThat(claimed).hasSize(1);
        UUID token = claimed.getFirst().getClaimToken();
        // 業務CLOSED_COMPLETE評価の後に別instance・同operation/episodeの発行TXが到着する。
        BindingEvent later = new BindingEvent(original.operationId(), original.episodeId(),
                Mode.NORMAL, LocalDateTime.now());
        publish(later);
        awaitPending(later.operationId());
        assertThat(inTx(() -> store().applyRecoveryEvaluation(waiting.getId(), token,
                ModuleEventRecoveryState.CLOSED_COMPLETE, now.plusSeconds(60), now))).isFalse();
        assertThat(inTx(() -> {
            entityManager.clear();
            return entityManager.find(ModuleEventNoticeOutboxEntity.class, waiting.getId()).getStatus();
        })).isEqualTo("WAITING_RECOVERY");
    }

    @Test
    void 同bindingのpendingが零ならCLOSED_COMPLETEの現世代を昇格できる() {
        BindingEvent event = event(Mode.NORMAL);
        Instant now = Instant.now();
        ModuleEventNoticeOutboxEntity waiting = waiting(event, now);
        List<ModuleEventNoticeOutboxEntity> claimed = inTx(() -> store().claimRecovery(1, now));
        assertThat(claimed).hasSize(1);
        assertThat(inTx(() -> store().applyRecoveryEvaluation(waiting.getId(),
                claimed.getFirst().getClaimToken(), ModuleEventRecoveryState.CLOSED_COMPLETE,
                now.plusSeconds(60), now))).isTrue();
    }

    private BindingEvent event(Mode mode) {
        UUID operation = UUID.randomUUID();
        operations.add(operation);
        return new BindingEvent(operation, UUID.randomUUID(), mode, LocalDateTime.now());
    }

    private void publish(BindingEvent event) {
        inTx(() -> {
            publisher.publishEvent(event);
            return null;
        });
        committedExecutions.merge(event.operationId(), 2, Integer::sum);
    }

    private void awaitPending(UUID operation) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication_attempts a JOIN event_publication p "
                        + "ON p.id=a.publication_id WHERE p.serialized_event LIKE ? AND a.status='PENDING'",
                Long.class, "%" + operation + "%")).isEqualTo(2L));
    }

    private List<byte[]> publicationIds(UUID operation) {
        return jdbc.queryForList("SELECT id FROM event_publication WHERE serialized_event LIKE ?",
                byte[].class, "%" + operation + "%");
    }

    private List<Map<String, Object>> bindingRows(UUID operation) {
        return jdbc.queryForList("SELECT a.resolver_key, HEX(a.operation_id) AS operation_hex, "
                + "HEX(a.episode_id) AS episode_hex, a.scope_type, a.scope_id, a.organization_id "
                + "FROM event_publication_attempts a JOIN event_publication p ON p.id=a.publication_id "
                + "WHERE p.serialized_event LIKE ?", "%" + operation + "%");
    }

    private void assertBinding(BindingEvent event) {
        List<Map<String, Object>> rows = bindingRows(event.operationId());
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row).containsEntry("resolver_key", RESOLVER_KEY)
                    .containsEntry("operation_hex", hex(event.operationId()))
                    .containsEntry("episode_hex", hex(event.episodeId()))
                    .containsEntry("scope_type", "TEAM").containsEntry("scope_id", SCOPE_ID)
                    .containsEntry("organization_id", null);
        }
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private void assertNoPublications(UUID operation) {
        assertThat(publicationIds(operation)).isEmpty();
    }

    private void deletePublications(UUID operation) {
        jdbc.update("DELETE a FROM event_publication_attempts a JOIN event_publication p "
                + "ON p.id=a.publication_id WHERE p.serialized_event LIKE ?", "%" + operation + "%");
        jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", "%" + operation + "%");
    }

    private ModuleEventNoticeOutboxEntity waiting(BindingEvent event, Instant now) {
        UUID key = UUID.randomUUID();
        noticeKeys.add(key);
        ModuleEventNoticeOutboxEntity row = ModuleEventNoticeOutboxEntity.builder()
                .id(UuidV7.generate()).noticeKey(key).resolverKey(RESOLVER_KEY)
                .operationId(event.operationId()).episodeId(event.episodeId())
                .scopeType("TEAM").scopeId(SCOPE_ID).organizationId(null).phase("RECOVERED")
                .audience("CURRENT_ADMINS").descriptorVersion(1).status("WAITING_RECOVERY")
                .attemptCount(0).nextAttemptAt(now).createdAt(now).updatedAt(now).build();
        return inTx(() -> new SimpleJpaRepository<ModuleEventNoticeOutboxEntity, UUID>(
                ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(row));
    }

    private ModuleEventNoticeOutboxStore store() {
        return new ModuleEventNoticeOutboxStore(entityManager);
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    enum Mode { NORMAL, INVALID, MULTIPLE, EMPTY }
    record BindingEvent(UUID operationId, UUID episodeId, Mode mode, LocalDateTime occurredAt)
            implements ReliableEvent { }
    record OrdinaryOnlyEvent(UUID operationId, LocalDateTime occurredAt) implements ReliableEvent { }

    static final class Probe {
        private final AtomicInteger identifyCalls = new AtomicInteger();
        private final AtomicInteger evaluationCalls = new AtomicInteger();
        private final AtomicBoolean identifyInTransaction = new AtomicBoolean();
        private final AtomicBoolean ordinaryObserved = new AtomicBoolean();
        private final Map<UUID, AtomicInteger> executions = new ConcurrentHashMap<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean Probe bindingProbe() { return new Probe(); }
        @Bean Listeners bindingListeners(Probe probe) { return new Listeners(probe); }
        @Bean ModuleEventImpactResolver primaryBindingResolver(Probe probe, EventPublicationRegistry registry) {
            return new Resolver(RESOLVER_KEY, true, probe, registry);
        }
        @Bean ModuleEventImpactResolver secondaryBindingResolver(Probe probe, EventPublicationRegistry registry) {
            return new Resolver("binding-it-secondary", false, probe, registry);
        }
    }

    static class Listeners {
        private final Probe probe;
        Listeners(Probe probe) { this.probe = probe; }
        @ModuleEventListener(id = "module-binding-it-first")
        public void first(BindingEvent event) { fail(event); }
        @ModuleEventListener(id = "module-binding-it-second")
        public void second(BindingEvent event) { fail(event); }
        @TransactionalEventListener(id = "module-binding-it-ordinary")
        public void ordinary(OrdinaryOnlyEvent event) { probe.ordinaryObserved.set(true); }
        private void fail(BindingEvent event) {
            probe.executions.computeIfAbsent(event.operationId(), ignored -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("MODULE_BINDING_IT");
        }
    }

    /** Registryを依存に持つ業務resolverでも、遅延窓口によって起動循環を作らない検体。 */
    static final class Resolver implements ModuleEventImpactResolver {
        private final String key;
        private final boolean primary;
        private final Probe probe;
        Resolver(String key, boolean primary, Probe probe, EventPublicationRegistry registry) {
            assertThat(registry).isNotNull();
            this.key = key;
            this.primary = primary;
            this.probe = probe;
        }
        @Override public String resolverKey() { return key; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent candidate) {
            if (primary) {
                probe.identifyCalls.incrementAndGet();
                probe.identifyInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            }
            if (!(candidate instanceof BindingEvent event) || event.mode() == Mode.EMPTY
                    || (!primary && event.mode() != Mode.MULTIPLE)) {
                return Optional.empty();
            }
            return Optional.of(new ModuleEventImpactBinding(key, event.operationId(), event.episodeId(),
                    ModuleEventNoticeScope.TEAM, event.mode() == Mode.INVALID ? 0L : SCOPE_ID, null));
        }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
            throw evaluationForbidden();
        }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(
                ModuleEventNoticeDescriptor notice, long afterUserId, int limit) {
            throw evaluationForbidden();
        }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(
                ModuleEventNoticeDescriptor notice, List<Long> candidateUserIds) {
            throw evaluationForbidden();
        }
        private AssertionError evaluationForbidden() {
            probe.evaluationCalls.incrementAndGet();
            return new AssertionError("初回storeから業務の現在評価を呼んではいけない");
        }
    }
}
