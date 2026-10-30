package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 自分のoperationだけに故障を注入する。公式公開SPIとSpring公開AOP以外を使わない。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventExecutionAppTestFixture {
    static final String FIRST = "execution-app-it-first";
    static final String SECOND = "execution-app-it-second";
    enum Fault { NONE, PUBLICATION_SECOND, HELPER_SECOND, DEFERRED_FLUSH, CLAIM_SQL }
    public record Event(UUID operationId, UUID episodeId, LocalDateTime occurredAt) implements ReliableEvent { }

    @Bean Control executionAppControl() { return new Control(); }
    @Bean ReadOnlyCaller executionAppReadOnlyCaller(ModuleEventPublicationStore store) {
        return new ReadOnlyCaller(store);
    }
    @Bean Listeners executionAppListeners(Control control) { return new Listeners(control); }
    @Bean ModuleEventImpactResolver executionAppResolver() { return new Resolver(); }
    @Bean static BeanPostProcessor executionAppFaults(ObjectProvider<Control> controls,
            ObjectProvider<EntityManager> managers) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                boolean official = name.equals("jpaEventPublicationRepository");
                boolean store = name.equals("moduleEventPublicationStore");
                if (!official && !store) return bean;
                ProxyFactory proxy = new ProxyFactory(bean);
                if (store) proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    Control control = controls.getObject();
                    String method = invocation.getMethod().getName();
                    Object[] args = invocation.getArguments();
                    if (official && method.equals("create") && args[0] instanceof TargetEventPublication publication
                            && publication.getEvent() instanceof Event event) {
                        control.publications.add(publication.getIdentifier());
                        if (event.operationId().equals(control.operation)
                                && control.publicationCalls.incrementAndGet() == 2
                                && control.fault == Fault.PUBLICATION_SECOND) {
                            throw new DataAccessResourceFailureException("EXECUTION_APP_PUBLICATION_SECOND");
                        }
                    }
                    if (store && method.equals("claim") && control.fault == Fault.CLAIM_SQL) {
                        throw new DataAccessResourceFailureException("EXECUTION_APP_CLAIM_SQL");
                    }
                    if (store && method.equals("createInitialAttempt") && args[2] instanceof ModuleEventImpactBinding binding
                            && binding.getOperationId().equals(control.operation)) {
                        int call = control.helperCalls.incrementAndGet();
                        if (call == 2 && control.fault == Fault.HELPER_SECOND) {
                            throw new DataAccessResourceFailureException("EXECUTION_APP_HELPER_SECOND");
                        }
                        Object result = invocation.proceed();
                        if (call == 2 && control.fault == Fault.DEFERRED_FLUSH
                                && result instanceof EventPublicationAttemptEntity attempt) {
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void beforeCommit(boolean readOnly) {
                                    EntityManager manager = managers.getObject();
                                    // 自検体のNOT NULL違反をRepository経由で作り、実flushへ渡す。
                                    try {
                                        new SimpleJpaRepository<EventPublicationAttemptEntity, UUID>(
                                                EventPublicationAttemptEntity.class, manager)
                                                .save(attempt.toBuilder().createdAt(null).build());
                                        control.flushEntered = true;
                                        manager.flush();
                                    } catch (RuntimeException failure) {
                                        control.flushFailure.set(failure);
                                        throw failure;
                                    }
                                }
                            });
                        }
                        return result;
                    }
                    return invocation.proceed();
                });
                return proxy.getProxy();
            }
        };
    }

    static final class Control {
        volatile Fault fault = Fault.NONE;
        volatile UUID operation;
        volatile boolean flushEntered;
        final AtomicReference<Throwable> flushFailure = new AtomicReference<>();
        final AtomicInteger publicationCalls = new AtomicInteger();
        final AtomicInteger helperCalls = new AtomicInteger();
        final Set<UUID> publications = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<UUID, AtomicInteger> bodies = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, Integer> committedBodies = new ConcurrentHashMap<>();
        void arm(Event event, Fault mode) {
            operation = event.operationId();
            fault = mode;
            publicationCalls.set(0);
            helperCalls.set(0);
            flushEntered = false;
            flushFailure.set(null);
        }
    }

    public static class ReadOnlyCaller {
        private final ModuleEventPublicationStore store;
        ReadOnlyCaller(ModuleEventPublicationStore store) { this.store = store; }
        @org.springframework.transaction.annotation.Transactional(readOnly = true)
        public boolean admit(ModuleEventExecutionFrame frame) { return store.admit(frame); }
    }

    static class Listeners {
        private final Control control;
        Listeners(Control control) { this.control = control; }
        @ModuleEventListener(id = FIRST) public void first(Event event) { receive(event); }
        @ModuleEventListener(id = SECOND) public void second(Event event) { receive(event); }
        private void receive(Event event) {
            control.bodies.computeIfAbsent(event.operationId(), ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    static final class Resolver implements ModuleEventImpactResolver {
        @Override public String resolverKey() { return "execution-app-it"; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent candidate) {
            if (!(candidate instanceof Event event)) return Optional.empty();
            return Optional.of(new ModuleEventImpactBinding(resolverKey(), event.operationId(), event.episodeId(),
                    ModuleEventNoticeScope.TEAM, 919299L, null));
        }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
            throw new AssertionError("初回保存から復旧評価を呼ばない");
        }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor notice,
                long afterUserId, int limit) { throw new AssertionError("受信者評価は禁止"); }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor notice,
                List<Long> candidates) { throw new AssertionError("受信者評価は禁止"); }
    }
}
