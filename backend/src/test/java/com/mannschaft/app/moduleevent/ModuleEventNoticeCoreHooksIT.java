package com.mannschaft.app.moduleevent;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.ReplicaRoutingAspect;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 未実行のcore接続試練。Lifecycleを直接呼ばず実Store.finish/Operations.monitorから通知予約まで追う。
 * rollbackはStore REQUIRES_NEW内部へ指示し、同TXで予約された事実も先にassertする。
 * 局所ContextはBeanFactoryだけを親へ結び、固定Ops警報を親errorreportへ伝播しない。
 * 実C/F業務・受信者・配信・実復旧CLOSED_COMPLETEの接続証明ではない。
 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeCoreHooksIT.Fixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("自己publicationとnoticeのみ、実Store advisorを同時変更しない")
class ModuleEventNoticeCoreHooksIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2100-01-01T01:00:00Z");
    private static final String RESOLVER = "notice-core-hooks-it";
    private static final String TARGET = "module-notice-core-hooks-it";
    private static final long SCOPE = 99231L;
    @Autowired private ConfigurableApplicationContext parent;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> ownPublications = new ArrayList<>();
    private final List<UUID> ownAttempts = new ArrayList<>();
    private final List<UUID> ownOperations = new ArrayList<>();
    private AnnotationConfigApplicationContext local;
    private ModuleEventPublicationStore store;
    private ModuleEventOperations operations;

    @BeforeEach
    void 実TXと運用入口だけを局所Contextへ組む() {
        local = new AnnotationConfigApplicationContext();
        local.getBeanFactory().setParentBeanFactory(parent.getBeanFactory());
        local.register(LocalTransactions.class);
        local.registerBean(EntityManager.class, () -> entityManager);
        local.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
        local.registerBean(SimpleMeterRegistry.class, definition -> definition.setPrimary(true));
        local.registerBean(ModuleEventPublicationStore.class);
        local.registerBean(ModuleEventOperations.class);
        local.refresh();
        assertThat(local.getParent()).isNull();
        store = local.getBean(ModuleEventPublicationStore.class);
        operations = local.getBean(ModuleEventOperations.class);
    }

    @AfterEach
    void 局所Contextと自己UUID行だけを解放する() {
        try {
            if (local != null) local.close();
        } finally {
            try {
                transaction(() -> {
                    new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteAllById(ownAttempts);
                    entityManager.flush();
                    publications.deletePublications(ownPublications);
                    if (!ownOperations.isEmpty()) entityManager.createQuery(
                            "delete from ModuleEventNoticeOutboxEntity n where n.operationId in :operations")
                            .setParameter("operations", ownOperations).executeUpdate();
                    return null;
                });
            } finally { DataSourceContextHolder.clear(); }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void 実finishの隔離又は成功削除と通知予約は同TXでcommit又はrollbackする(boolean completed, boolean rollback) {
        Seed fixture = seed(completed ? 0 : 10, ModuleEventAttemptStatus.IN_PROGRESS, NOW.minusSeconds(1));
        if (completed) seedImpact(fixture);
        String phase = completed ? "RECOVERED" : "QUARANTINED";
        String status = completed ? "WAITING_RECOVERY" : "PENDING";
        AtomicInteger inside = new AtomicInteger();
        afterTransaction(store, invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("finish")) {
                entityManager.flush();
                // 無hookのrollbackが後段ゼロだけで偽passしないよう、未commit予約2件を必須にする。
                assertPhase(fixture.event(), phase, status);
                assertThat(mainCount(fixture.frame().publicationId())).isEqualTo(completed ? 0 : 1);
                if (completed) assertThat(helper(fixture)).isEmpty();
                else assertThat(helper(fixture).orElseThrow().getStatus()).isEqualTo(ModuleEventAttemptStatus.QUARANTINED);
                inside.incrementAndGet();
                if (rollback) TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return result;
        });
        store.finish(fixture.frame(), completed);
        assertThat(inside).hasValue(1);
        if (rollback) {
            assertThat(mainCount(fixture.frame().publicationId())).isEqualTo(1);
            EventPublicationAttemptEntity unchanged = helper(fixture).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ModuleEventAttemptStatus.IN_PROGRESS);
            assertThat(unchanged.getClaimToken()).isEqualTo(fixture.frame().token());
            assertThat(unchanged.getRetryCount()).isEqualTo(completed ? 0 : 10);
            assertThat(notices(fixture.event(), phase)).isEmpty();
        } else {
            assertPhase(fixture.event(), phase, status);
            assertThat(mainCount(fixture.frame().publicationId())).isEqualTo(completed ? 0 : 1);
            if (completed) assertThat(helper(fixture)).isEmpty();
            else assertThat(helper(fixture).orElseThrow().getStatus()).isEqualTo(ModuleEventAttemptStatus.QUARANTINED);
        }
        if (completed) assertPhase(fixture.event(), "DELAYED", "PENDING");
        assertThat(notices(fixture.event(), completed ? "QUARANTINED" : "RECOVERED")).isEmpty();
    }

    @Test
    void 運用monitor入口が継続遅延二audienceを予約する() {
        Seed fixture = seed(0, ModuleEventAttemptStatus.PENDING, NOW.minusSeconds(1801));
        operations.monitor(NOW);
        assertPhase(fixture.event(), "DELAYED", "PENDING");
        assertThat(notices(fixture.event(), "RECOVERED")).isEmpty();
        assertThat(notices(fixture.event(), "QUARANTINED")).isEmpty();
        assertThat(mainCount(fixture.frame().publicationId())).isEqualTo(1);
        assertThat(helper(fixture).orElseThrow().getStatus()).isEqualTo(ModuleEventAttemptStatus.PENDING);
    }

    private Seed seed(int retry, ModuleEventAttemptStatus state, Instant original) {
        HookEvent event = new HookEvent(UUID.randomUUID(), UUID.randomUUID(), null, LocalDateTime.ofInstant(original, SERVER_ZONE));
        ownOperations.add(event.operationId());
        return transaction(() -> {
            TargetEventPublication publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of(TARGET), original));
            ownPublications.add(publication.getIdentifier());
            Instant now = NOW;
            EventPublicationAttemptEntity attempt = new SimpleJpaRepository<EventPublicationAttemptEntity, UUID>(
                    EventPublicationAttemptEntity.class, entityManager).saveAndFlush(EventPublicationAttemptEntity.builder()
                    .id(UuidV7.generate()).publicationId(publication.getIdentifier()).status(state).retryCount(retry)
                    .claimToken(UUID.randomUUID()).lastAttemptAt(now).leaseUntil(now.plusSeconds(300))
                    .resolverKey(RESOLVER).operationId(event.operationId()).episodeId(event.episodeId())
                    .scopeType(ModuleEventNoticeScope.TEAM).scopeId(SCOPE).organizationId(null)
                    .createdAt(now).updatedAt(now).version(0).build());
            ownAttempts.add(attempt.getId());
            return new Seed(event, new ModuleEventExecutionFrame(event, publication.getIdentifier(), attempt.getId(),
                    attempt.getClaimToken(), TARGET, false));
        });
    }

    private void seedImpact(Seed fixture) {
        transaction(() -> {
            for (ModuleEventNoticeAudience audience : ModuleEventNoticeAudience.values()) {
                new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(
                        ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate()).noticeKey(UUID.randomUUID())
                                .resolverKey(RESOLVER).operationId(fixture.event().operationId()).episodeId(fixture.event().episodeId())
                                .scopeType("TEAM").scopeId(SCOPE).organizationId(null).phase("DELAYED").audience(audience.name())
                                .descriptorVersion(1).status("PENDING").attemptCount(0).nextAttemptAt(NOW)
                                .createdAt(NOW).updatedAt(NOW).build());
            }
            return null;
        });
    }

    private Optional<EventPublicationAttemptEntity> helper(Seed seed) {
        return read(() -> new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).findById(seed.frame().attemptId()));
    }
    private long mainCount(UUID id) {
        return read(() -> ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM event_publication WHERE id = :id")
                .setParameter("id", java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits()).array()).getSingleResult()).longValue());
    }
    private List<ModuleEventNoticeOutboxEntity> notices(HookEvent event, String phase) {
        return read(() -> entityManager.createQuery("select n from ModuleEventNoticeOutboxEntity n "
                        + "where n.resolverKey = :resolver and n.operationId = :operation and n.episodeId = :episode and n.phase = :phase",
                ModuleEventNoticeOutboxEntity.class).setParameter("resolver", RESOLVER)
                .setParameter("operation", event.operationId()).setParameter("episode", event.episodeId())
                .setParameter("phase", phase).getResultList());
    }
    private void assertPhase(HookEvent event, String phase, String status) {
        List<ModuleEventNoticeOutboxEntity> rows = notices(event, phase);
        assertThat(rows).hasSize(2).extracting(ModuleEventNoticeOutboxEntity::getAudience)
                .containsExactlyInAnyOrder("CURRENT_ADMINS", "AFFECTED_USERS");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(status);
            assertThat(row.getDescriptorVersion()).isEqualTo(1);
            assertThat(row.getScopeType()).isEqualTo("TEAM");
            assertThat(row.getScopeId()).isEqualTo(SCOPE);
            assertThat(row.getOrganizationId()).isNull();
            assertThat(row.getId().version()).isEqualTo(7);
            assertThat(row.getNoticeRef()).isPositive();
            assertThat(row.getClaimToken()).isNull();
        });
    }
    private <T> T read(Supplier<T> action) {
        // REQUIREDなので、TX内部assertでは未commitの予約を同じ接続で確認する。
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }
    private <T> T transaction(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> action.get());
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

    private record Seed(HookEvent event, ModuleEventExecutionFrame frame) { }
    public record HookEvent(UUID operationId, UUID episodeId, Long organizationId, LocalDateTime occurredAt)
            implements ReliableEvent { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean ModuleEventImpactResolver coreHooksResolver() { return new Resolver(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    private static class LocalTransactions {
        @Bean ReplicaRoutingAspect actualReplicaRoutingAspect() { return new ReplicaRoutingAspect(); }
    }
    private static final class Resolver implements ModuleEventImpactResolver {
        @Override public String resolverKey() { return RESOLVER; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent candidate) {
            if (!(candidate instanceof HookEvent event)) return Optional.empty();
            return Optional.of(new ModuleEventImpactBinding(RESOLVER, event.operationId(), event.episodeId(),
                    ModuleEventNoticeScope.TEAM, SCOPE, null));
        }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) { throw forbidden(); }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor notice, long after, int limit) { throw forbidden(); }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor notice, List<Long> candidates) { throw forbidden(); }
        private AssertionError forbidden() { return new AssertionError("core hookのM TXから業務現在評価を呼んではいけない"); }
    }
}
