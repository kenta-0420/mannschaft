package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知接続試練専用の実 SPI 検体。M/通知 Bean・DB は実物を使う。
 * 現在判定の伝播を検査するもので、C/F 実業務の認可接続を合格にしない。
 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventNoticeNotificationFixtures {
    static final String KEY = "notification-adapter-it";
    @Bean Resolver moduleEventNotificationResolver() { return new Resolver(); }

    static final class State {
        volatile ModuleEventRecoveryState recovery = ModuleEventRecoveryState.OPEN;
        volatile List<ModuleEventNoticeRecipient> recipients = List.of();
        volatile boolean available = true;
        volatile boolean observedOutsideTransaction = true;
    }

    static final class Resolver implements ModuleEventImpactResolver {
        final Map<UUID, State> operations = new ConcurrentHashMap<>();
        @Override public String resolverKey() { return KEY; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) { return Optional.empty(); }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
            State state = state(binding);
            observe(state);
            return state.available ? state.recovery : ModuleEventRecoveryState.UNKNOWN;
        }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor descriptor,
                long cursor, int limit) {
            State state = state(descriptor.getImpact());
            observe(state);
            return state.recipients.stream().filter(r -> r.getUserId() > cursor).limit(limit).toList();
        }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor descriptor,
                List<Long> candidates) {
            State state = state(descriptor.getImpact());
            observe(state);
            return state.recipients.stream().filter(r -> candidates.contains(r.getUserId())).toList();
        }
        private State state(ModuleEventImpactBinding binding) {
            State state = operations.get(binding.getOperationId());
            if (state == null) throw new IllegalStateException("自試練以外の操作は評価しない");
            return state;
        }
        private void observe(State state) {
            state.observedOutsideTransaction &= !TransactionSynchronizationManager.isActualTransactionActive();
        }
    }
}
