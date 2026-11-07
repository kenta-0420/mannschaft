package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** この IT だけへ明示 import する業務 SPI 検体。本番業務の active/role/tenant 接続済みを意味しない。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventNoticeOutboxApiFixtures {
    static final String RESOLVER_KEY = "notice-api-it";

    @Bean
    FixtureResolver moduleEventNoticeApiFixtureResolver(EntityManagerFactory factory, DataSource dataSource) {
        return new FixtureResolver(factory, dataSource);
    }

    static final class Scenario {
        volatile ModuleEventRecoveryState recovery = ModuleEventRecoveryState.OPEN;
        volatile List<ModuleEventNoticeRecipient> recipients = List.of();
        volatile Runnable afterClosedDecision;
        volatile boolean badPage;
        volatile boolean observedNoTransaction = true;
        final AtomicInteger recoveryCalls = new AtomicInteger();
        final AtomicInteger pageCalls = new AtomicInteger();
        final AtomicInteger eligibleCalls = new AtomicInteger();
    }

    static final class FixtureResolver implements ModuleEventImpactResolver {
        private final EntityManagerFactory factory;
        private final DataSource dataSource;
        final Map<UUID, Scenario> scenarios = new ConcurrentHashMap<>();

        FixtureResolver(EntityManagerFactory factory, DataSource dataSource) {
            this.factory = factory;
            this.dataSource = dataSource;
        }

        @Override public String resolverKey() { return RESOLVER_KEY; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) { return Optional.empty(); }

        @Override
        public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
            Scenario scenario = scenario(binding.getOperationId());
            observe(scenario);
            scenario.recoveryCalls.incrementAndGet();
            ModuleEventRecoveryState decision = scenario.recovery;
            Runnable afterDecision = scenario.afterClosedDecision;
            if (decision == ModuleEventRecoveryState.CLOSED_COMPLETE && afterDecision != null
                    && scenario.observedNoTransaction) {
                scenario.afterClosedDecision = null;
                afterDecision.run();
            }
            return decision;
        }

        @Override
        public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor descriptor,
                long afterUserId, int limit) {
            Scenario scenario = scenario(descriptor.getImpact().getOperationId());
            observe(scenario);
            scenario.pageCalls.incrementAndGet();
            return scenario.badPage ? null : scenario.recipients.stream()
                    .filter(recipient -> recipient.getUserId() > afterUserId).limit(limit).toList();
        }

        @Override
        public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor descriptor,
                List<Long> candidates) {
            Scenario scenario = scenario(descriptor.getImpact().getOperationId());
            observe(scenario);
            scenario.eligibleCalls.incrementAndGet();
            return scenario.recipients.stream().filter(recipient -> candidates.contains(recipient.getUserId())).toList();
        }

        private Scenario scenario(UUID operation) {
            Scenario scenario = scenarios.get(operation);
            if (scenario == null) throw new IllegalStateException("自検体以外の業務評価を呼んだ");
            return scenario;
        }

        private void observe(Scenario scenario) {
            scenario.observedNoTransaction &= !TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.hasResource(factory)
                    && !TransactionSynchronizationManager.hasResource(dataSource);
        }
    }
}
