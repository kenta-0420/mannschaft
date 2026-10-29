package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** M18専用の実購読と観測。人工業務待機・mock・他domain schedulerは追加しない。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventLoadTestFixture {
    static final String LISTENER_ID = "module-load-it";
    enum Traffic { WARMUP, BACKLOG, NORMAL }
    public record LoadEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }

    @Bean Probe moduleLoadProbe() { return new Probe(); }
    @Bean Listeners moduleLoadListeners(Probe probe) { return new Listeners(probe); }

    public static class Listeners {
        private final Probe probe;
        Listeners(Probe probe) { this.probe = probe; }
        @ModuleEventListener(id = LISTENER_ID)
        public void receive(LoadEvent event) {
            Entry entry = probe.entries.get(event.targetId());
            if (entry == null) throw new IllegalStateException("M18自己検体が未登録です");
            entry.listenerCalls.incrementAndGet();
        }
    }

    static final class Entry {
        final UUID id = UUID.randomUUID();
        final Traffic traffic;
        final java.util.Set<UUID> publications = ConcurrentHashMap.newKeySet();
        final AtomicLong committed = new AtomicLong();
        final AtomicLong finished = new AtomicLong();
        final AtomicInteger listenerCalls = new AtomicInteger();
        final AtomicInteger finishCalls = new AtomicInteger();
        volatile long planned;
        volatile long arrived;
        volatile String failure;
        Entry(Traffic traffic) { this.traffic = traffic; }
    }

    static final class Probe {
        final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();
        final ThreadLocal<Entry> publishing = new ThreadLocal<>();
        final AtomicInteger physicalRejected = new AtomicInteger();
        final AtomicInteger frameRejected = new AtomicInteger();
        final AtomicInteger replayClaimed = new AtomicInteger();
        final AtomicInteger replayAdmitted = new AtomicInteger();
        final AtomicInteger maxClaimLimit = new AtomicInteger();
        final List<long[]> ticks = new CopyOnWriteArrayList<>();
        final List<long[]> samples = new CopyOnWriteArrayList<>();
        Entry create(Traffic traffic) {
            Entry entry = new Entry(traffic);
            entries.put(entry.id, entry);
            return entry;
        }
        Entry entry(ModuleEventExecutionFrame frame) {
            return frame.event() instanceof LoadEvent event ? entries.get(event.targetId()) : null;
        }
        Map<String, Object> entryResult(Entry entry, long start) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", entry.id.toString());
            result.put("plannedNs", entry.planned - start);
            result.put("arrivedNs", entry.arrived == 0 ? null : entry.arrived - start);
            result.put("committedNs", entry.committed.get() == 0 ? null : entry.committed.get() - start);
            result.put("finishedNs", entry.finished.get() == 0 ? null : entry.finished.get() - start);
            result.put("failure", entry.failure);
            result.put("listenerCalls", entry.listenerCalls.get());
            result.put("finishCalls", entry.finishCalls.get());
            result.put("publicationCount", entry.publications.size());
            return result;
        }
    }

    /** 既存TX adviceより外側へ1advisorだけ追加し、commit後の別接続DB照合もlatencyへ含める。 */
    static final class StoreObservation implements AutoCloseable {
        private final Advised proxy;
        private final DefaultPointcutAdvisor advisor;
        private final java.util.concurrent.ThreadPoolExecutor physical;
        private final RejectedExecutionHandler previousHandler;
        StoreObservation(ModuleEventPublicationStore store, ModuleEventTaskExecutor pool, Probe probe, JdbcTemplate jdbc) {
            proxy = (Advised) store;
            physical = pool.getThreadPoolExecutor();
            previousHandler = physical.getRejectedExecutionHandler();
            physical.setRejectedExecutionHandler((task, executor) -> {
                probe.physicalRejected.incrementAndGet();
                previousHandler.rejectedExecution(task, executor);
            });
            advisor = new DefaultPointcutAdvisor(new StaticMethodMatcherPointcut() {
                @Override public boolean matches(Method method, Class<?> type) {
                    return ModuleEventPublicationStore.class.isAssignableFrom(type)
                            && List.of("createInitialAttempt", "claim", "admit", "finish", "rejected").contains(method.getName());
                }
            }, (MethodInterceptor) invocation -> {
                String method = invocation.getMethod().getName();
                Object[] args = invocation.getArguments();
                Entry entry = args.length > 0 && args[0] instanceof ModuleEventExecutionFrame frame ? probe.entry(frame) : null;
                if (method.equals("createInitialAttempt") && probe.publishing.get() != null) {
                    probe.publishing.get().publications.add((UUID) args[0]);
                }
                if (method.equals("rejected") && entry != null) probe.frameRejected.incrementAndGet();
                Object result = invocation.proceed();
                if (method.equals("claim")) {
                    probe.maxClaimLimit.accumulateAndGet((Integer) args[2], Math::max);
                    for (Object value : (List<?>) result) {
                        ModuleEventPublicationStore.Claim claim = (ModuleEventPublicationStore.Claim) value;
                        Entry claimed = probe.entry(claim.frame());
                        if (claimed != null && claimed.traffic == Traffic.BACKLOG) probe.replayClaimed.incrementAndGet();
                    }
                }
                if (entry != null && method.equals("admit") && Boolean.TRUE.equals(result)
                        && !((ModuleEventExecutionFrame) args[0]).initial()) probe.replayAdmitted.incrementAndGet();
                if (entry != null && method.equals("finish") && Boolean.TRUE.equals(args[1])) {
                    ModuleEventExecutionFrame frame = (ModuleEventExecutionFrame) args[0];
                    entry.finishCalls.incrementAndGet();
                    Long remaining = jdbc.queryForObject("""
                            SELECT (SELECT COUNT(*) FROM event_publication WHERE id = ?)
                                 + (SELECT COUNT(*) FROM event_publication_attempts WHERE publication_id = ?)
                            """, Long.class, bytes(frame.publicationId()), bytes(frame.publicationId()));
                    if (TransactionSynchronizationManager.isActualTransactionActive() || remaining == null || remaining != 0L) {
                        entry.failure = "FINISH_NOT_COMMITTED";
                    } else if (entry.traffic != Traffic.BACKLOG && entry.committed.get() == 0) {
                        entry.failure = "FINISH_BEFORE_PUBLISHER_COMMIT";
                    } else entry.finished.compareAndSet(0, System.nanoTime());
                }
                return result;
            });
            proxy.addAdvisor(0, advisor);
        }
        @Override public void close() {
            if (physical.getActiveCount() != 0 || !physical.getQueue().isEmpty()) {
                throw new IllegalStateException("自己pool終端前にはobserverを外せません");
            }
            proxy.removeAdvisor(advisor);
            physical.setRejectedExecutionHandler(previousHandler);
        }
    }

    /** ApplicationContext親を設定せずBeanFactoryだけを親にし、親へのevent再伝播を避ける。 */
    static AnnotationConfigApplicationContext startScheduler(ConfigurableApplicationContext parent, Probe probe,
            JdbcTemplate jdbc, String table) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.getBeanFactory().setParentBeanFactory(parent.getBeanFactory());
        child.registerBean("lockProvider", LockProvider.class, () -> new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc)
                        .withTableName(table).usingDbTime().build()));
        child.registerBean("m18ReplayBatch", ModuleEventReplayBatch.class, () -> new ModuleEventReplayBatch(
                parent.getBean(IncompleteEventPublications.class), parent.getBean(ModuleEventOperations.class)));
        child.register(Scheduling.class);
        try { child.refresh(); return child; }
        catch (RuntimeException | Error failure) { child.close(); throw failure; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT3M", order = 15)
    private static class Scheduling {
        @Bean(destroyMethod = "shutdown") ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("m18-schedule-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(15);
            return scheduler;
        }
        @Bean DefaultPointcutAdvisor m18TickObserver(Probe probe) {
            DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(new StaticMethodMatcherPointcut() {
                @Override public boolean matches(Method method, Class<?> type) {
                    return ModuleEventReplayBatch.class.isAssignableFrom(type) && method.getName().equals("poll");
                }
            }, (MethodInterceptor) invocation -> {
                LockAssert.assertLocked();
                long[] tick = {System.nanoTime(), 0L};
                probe.ticks.add(tick);
                try { return invocation.proceed(); }
                finally { tick[1] = System.nanoTime(); }
            });
            advisor.setOrder(16);
            return advisor;
        }
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
