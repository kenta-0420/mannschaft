package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.EventPublicationAttemptEntity;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeClaim;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公開 claim が復旧待ちを評価する先行試練。試験から Store の昇格操作を直接呼ばない。
 * 否定系も先送り時刻と lease 解放を要求し、何もしない空結果を合格にしない。
 * 専用 provider は業務検体。実 C/F の durable close、配送、最新 main 統合は別 AC。
 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeRecoveryPumpIT.Fixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("自分の未来時刻の復旧予約だけを公開 claim で処理する")
class ModuleEventNoticeRecoveryPumpIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2100-02-01T00:00:00Z");
    private static final String KEY = "recovery-pump-it";
    private final List<UUID> notices = new ArrayList<>();
    private final List<UUID> attempts = new ArrayList<>();
    private final List<UUID> publicationIds = new ArrayList<>();
    private final List<UUID> operations = new ArrayList<>();
    @Autowired private ModuleEventNoticeOutboxApi api;
    @Autowired private Probe resolver;
    @Autowired private ReadOnlyCaller caller;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory factory;
    @Autowired private DataSource dataSource;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 自分の検体だけをRepositoryで片付ける() {
        try {
            tx(() -> {
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteAllById(attempts);
                if (!publicationIds.isEmpty()) publications.deletePublications(publicationIds);
                new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).deleteAllById(notices);
                return null;
            });
        } finally {
            operations.forEach(resolver.scenarios::remove);
        }
    }

    @Test
    void CLOSED_COMPLETEと未完了ゼロなら両audienceを本物claimで取得する() {
        ModuleEventImpactBinding binding = binding();
        Scenario scenario = scenario(binding);
        ModuleEventNoticeOutboxEntity admins = waiting(binding, "CURRENT_ADMINS");
        ModuleEventNoticeOutboxEntity users = waiting(binding, "AFFECTED_USERS");
        List<ModuleEventNoticeClaim> claims = api.claim(100, NOW);
        assertThat(claims).as("公開 claim から復旧 pump が呼ばれる必要がある").hasSize(2);
        assertThat(claims).extracting(ModuleEventNoticeClaim::getId)
                .containsExactlyInAnyOrder(admins.getId(), users.getId());
        assertThat(claims).allSatisfy(claim -> {
            assertThat(claim.getDescriptor()).isPresent();
            assertThat(claim.getDescriptor().orElseThrow().getPhase()).isEqualTo(ModuleEventNoticePhase.RECOVERED);
            assertThat(claim.getAttemptCount()).isEqualTo(3);
            assertThat(claim.getClaimToken()).isNotNull();
        });
        assertThat(claims).extracting(c -> c.getDescriptor().orElseThrow().getAudience())
                .containsExactlyInAnyOrder(ModuleEventNoticeAudience.CURRENT_ADMINS, ModuleEventNoticeAudience.AFFECTED_USERS);
        assertThat(load(admins).getStatus()).isEqualTo("RELAYING");
        assertThat(load(users).getStatus()).isEqualTo("RELAYING");
        assertThat(scenario.calls.get()).isBetween(1, 2);
        assertThat(scenario.noTransaction).isTrue();
    }

    @Test
    void M未完了があれば業務SPIを呼ばず実際に先送りする() {
        ModuleEventImpactBinding binding = binding();
        ModuleEventNoticeOutboxEntity row = waiting(binding, "CURRENT_ADMINS");
        pending(binding);
        assertThat(api.claim(100, NOW)).isEmpty();
        assertDeferred(row);
        assertThat(scenario(binding).calls).hasValue(0);
        assertThat(tx(() -> new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                .existsById(attempts.getFirst()))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "UNKNOWN"})
    void 未完了の業務状態はWAITINGを保ち次回だけを進める(String state) {
        ModuleEventImpactBinding binding = binding();
        scenario(binding).state = ModuleEventRecoveryState.valueOf(state);
        ModuleEventNoticeOutboxEntity row = waiting(binding, "CURRENT_ADMINS");
        assertThat(api.claim(100, NOW)).isEmpty();
        assertDeferred(row);
        assertThat(scenario(binding).calls).hasValue(1);
        assertThat(scenario(binding).noTransaction).isTrue();
    }

    @Test
    void 未接続と例外とnullを先送りし後続正常行を飢餓化しない() {
        ModuleEventImpactBinding missing = binding("recovery-pump-missing");
        ModuleEventImpactBinding failure = binding();
        scenario(failure).failure = true;
        ModuleEventImpactBinding nil = binding();
        scenario(nil).state = null;
        List<ModuleEventNoticeOutboxEntity> blocked = List.of(waiting(missing, "CURRENT_ADMINS"),
                waiting(failure, "CURRENT_ADMINS"), waiting(nil, "CURRENT_ADMINS"));
        ModuleEventNoticeOutboxEntity healthy = waiting(binding(), "CURRENT_ADMINS");
        List<ModuleEventNoticeClaim> claims = api.claim(100, NOW);
        assertThat(claims).extracting(ModuleEventNoticeClaim::getId).containsExactly(healthy.getId());
        blocked.forEach(this::assertDeferred);
        assertThat(scenario(missing).calls).hasValue(0);
        assertThat(scenario(failure).calls).hasValue(1);
        assertThat(scenario(nil).calls).hasValue(1);
    }

    @Test
    void SPI評価中に後発publicationがcommitされたら復旧昇格を拒否する() {
        ModuleEventImpactBinding binding = binding();
        ModuleEventNoticeOutboxEntity row = waiting(binding, "CURRENT_ADMINS");
        scenario(binding).afterDecision = () -> pending(binding);
        assertThat(api.claim(100, NOW)).isEmpty();
        assertThat(scenario(binding).calls).hasValue(1);
        assertThat(publicationIds).hasSize(1);
        assertDeferred(row);
        assertThat(tx(() -> new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                .existsById(attempts.getFirst()))).isTrue();
    }

    @Test
    void readonlyCallerを停止してSPIへTXresourceを持ち込まず元callerを復帰する() {
        ModuleEventImpactBinding binding = binding();
        ModuleEventNoticeOutboxEntity row = waiting(binding, "CURRENT_ADMINS");
        List<ModuleEventNoticeClaim> claims = caller.call(() -> {
            Object persistence = TransactionSynchronizationManager.getResource(factory);
            Object jdbc = TransactionSynchronizationManager.getResource(dataSource);
            assertThat(persistence).isNotNull();
            assertThat(jdbc).isNotNull();
            List<ModuleEventNoticeClaim> result = api.claim(100, NOW);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(factory)).isSameAs(persistence);
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(jdbc);
            return result;
        });
        assertThat(scenario(binding).calls).hasValue(1);
        assertThat(scenario(binding).noTransaction).isTrue();
        assertThat(claims).extracting(ModuleEventNoticeClaim::getId).containsExactly(row.getId());
        assertThat(load(row).getStatus()).isEqualTo("RELAYING");
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 1})
    void 未知版と既知版不正descriptorは元の版とattemptを保ち後続へ進む(int version) {
        ModuleEventImpactBinding binding = binding();
        ModuleEventNoticeOutboxEntity broken = save(draft(binding, "CURRENT_ADMINS").toBuilder()
                .descriptorVersion(version).scopeType("FUTURE").build());
        ModuleEventNoticeOutboxEntity healthy = waiting(binding(), "CURRENT_ADMINS");
        List<ModuleEventNoticeClaim> claims = api.claim(100, NOW);
        assertThat(claims).extracting(ModuleEventNoticeClaim::getId).containsExactly(healthy.getId());
        assertDeferred(broken);
        assertThat(load(broken).getDescriptorVersion()).isEqualTo(version);
        assertThat(load(broken).getScopeType()).isEqualTo("FUTURE");
        assertThat(scenario(binding).calls).hasValue(0);
    }

    private void assertDeferred(ModuleEventNoticeOutboxEntity original) {
        ModuleEventNoticeOutboxEntity row = load(original);
        assertThat(row.getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(row.getPhase()).isEqualTo("RECOVERED");
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getClaimedAt()).isNull();
        assertThat(row.getAttemptCount()).isEqualTo(original.getAttemptCount());
        assertThat(row.getNextAttemptAt()).as("空結果だけでなくdurableな先送りが必要")
                .isAfter(NOW).isBeforeOrEqualTo(NOW.plusSeconds(3600));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getNoticeKey()).isEqualTo(original.getNoticeKey());
        assertThat(row.getNoticeRef()).isEqualTo(original.getNoticeRef());
    }

    private ModuleEventImpactBinding binding() { return binding(KEY); }
    private ModuleEventImpactBinding binding(String key) {
        ModuleEventImpactBinding binding = new ModuleEventImpactBinding(key, UUID.randomUUID(), UUID.randomUUID(),
                ModuleEventNoticeScope.TEAM, 8411L, null);
        operations.add(binding.getOperationId());
        resolver.scenarios.put(binding.getOperationId(), new Scenario());
        return binding;
    }
    private Scenario scenario(ModuleEventImpactBinding binding) { return resolver.scenarios.get(binding.getOperationId()); }
    private ModuleEventNoticeOutboxEntity waiting(ModuleEventImpactBinding binding, String audience) {
        return save(draft(binding, audience));
    }
    private ModuleEventNoticeOutboxEntity draft(ModuleEventImpactBinding binding, String audience) {
        UUID id = UuidV7.generate();
        notices.add(id);
        return ModuleEventNoticeOutboxEntity.builder().id(id).noticeKey(UUID.randomUUID())
                .resolverKey(binding.getResolverKey()).operationId(binding.getOperationId()).episodeId(binding.getEpisodeId())
                .scopeType(binding.getScopeType().name()).scopeId(binding.getScopeId()).organizationId(binding.getOrganizationId())
                .phase("RECOVERED").audience(audience).descriptorVersion(1).status("WAITING_RECOVERY")
                .attemptCount(3).nextAttemptAt(NOW).createdAt(NOW.minusSeconds(1)).updatedAt(NOW.minusSeconds(1)).build();
    }
    private ModuleEventNoticeOutboxEntity save(ModuleEventNoticeOutboxEntity row) {
        return tx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(row));
    }
    private ModuleEventNoticeOutboxEntity load(ModuleEventNoticeOutboxEntity row) {
        return tx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager)
                .findById(row.getId()).orElseThrow());
    }
    private void pending(ModuleEventImpactBinding binding) {
        tx(() -> {
            LocalDateTime time = LocalDateTime.ofInstant(NOW, SERVER_ZONE);
            TargetEventPublication publication = publications.create(TargetEventPublication.of(
                    new PumpEvent(UUID.randomUUID(), null, time),
                    PublicationTargetIdentifier.of("recovery-pump-it-" + UUID.randomUUID()), NOW));
            publicationIds.add(publication.getIdentifier());
            UUID id = UuidV7.generate();
            attempts.add(id);
            new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).saveAndFlush(
                    EventPublicationAttemptEntity.builder().id(id).publicationId(publication.getIdentifier())
                            .retryCount(0).status(ModuleEventAttemptStatus.PENDING).claimToken(UUID.randomUUID())
                            .lastAttemptAt(time).leaseUntil(time.plusMinutes(5)).resolverKey(binding.getResolverKey())
                            .operationId(binding.getOperationId()).episodeId(binding.getEpisodeId())
                            .scopeType(binding.getScopeType()).scopeId(binding.getScopeId()).organizationId(binding.getOrganizationId())
                            .createdAt(time).updatedAt(time).version(0).build());
            return null;
        });
    }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }

    /** final と明示 import により暗黙 nested config の候補にしない。 */
    @TestConfiguration(proxyBeanMethods = false)
    static final class Fixtures {
        @Bean Probe recoveryPumpFixtureResolver(EntityManagerFactory factory, DataSource dataSource) {
            return new Probe(factory, dataSource);
        }
        @Bean ReadOnlyCaller recoveryPumpReadOnlyCaller() { return new ReadOnlyCaller(); }
    }
    public static class ReadOnlyCaller {
        @Transactional(readOnly = true)
        public <T> T call(Supplier<T> action) { return action.get(); }
    }
    static final class Scenario {
        ModuleEventRecoveryState state = ModuleEventRecoveryState.CLOSED_COMPLETE;
        boolean failure;
        boolean noTransaction = true;
        Runnable afterDecision;
        final AtomicInteger calls = new AtomicInteger();
    }
    static final class Probe implements ModuleEventImpactResolver {
        final Map<UUID, Scenario> scenarios = new ConcurrentHashMap<>();
        private final EntityManagerFactory factory;
        private final DataSource dataSource;
        Probe(EntityManagerFactory factory, DataSource dataSource) { this.factory = factory; this.dataSource = dataSource; }
        @Override public String resolverKey() { return KEY; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) { return Optional.empty(); }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
            Scenario scenario = scenarios.get(binding.getOperationId());
            if (scenario == null) throw new IllegalStateException("自分の検体以外を評価した");
            scenario.calls.incrementAndGet();
            scenario.noTransaction &= !TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.hasResource(factory)
                    && !TransactionSynchronizationManager.hasResource(dataSource);
            if (scenario.failure) throw new IllegalStateException("検体の一時障害");
            if (scenario.afterDecision != null && scenario.noTransaction) {
                Runnable callback = scenario.afterDecision;
                scenario.afterDecision = null;
                callback.run();
            }
            return scenario.state;
        }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor notice,
                long afterUserId, int limit) { throw new IllegalStateException("復旧pumpは受信者を評価しない"); }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor notice,
                List<Long> candidates) { throw new IllegalStateException("復旧pumpは受信者を評価しない"); }
    }
    record PumpEvent(UUID fixtureId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }
}
