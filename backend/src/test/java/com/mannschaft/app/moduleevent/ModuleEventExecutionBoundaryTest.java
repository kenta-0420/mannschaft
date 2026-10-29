package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 主App/DBなし。実Spring Async proxy・実worker・実queueで世代搬送を確認する。 */
class ModuleEventExecutionBoundaryTest {
    @Test
    void 標準Asyncより外側へcaptureを装着する() {
        try (Harness h = new Harness(false)) {
            assertThat(h.spring.getBean(ModuleEventInvocationCapturePostProcessor.class))
                    .isNotInstanceOf(Ordered.class);
            Advised proxy = (Advised) h.marker();
            assertThat(proxy.getAdvisors()[0].getAdvice().getClass().getName())
                    .contains("ModuleEventInvocationCapturePostProcessor");
        }
    }

    @Test
    void immutableFrameをworkerへ渡し入口受理後だけ業務を呼ぶ() throws Exception {
        try (Harness h = new Harness(false)) {
            Event event = event();
            ModuleEventExecutionFrame frame = h.bind(event);
            h.marker().handle(event);
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.frames).containsExactly(frame);
            assertThat(h.owner.accepted).hasValue(1);
            assertThat(h.probe.order).containsExactly("accept", "body");
            assertThat(h.context.submission()).isEmpty();
            assertThat(h.context.execution()).isEmpty();
        }
    }

    @Test
    void lazySingletonにも取得時点で外側captureを装着する() throws Exception {
        try (Harness h = new Harness(true)) {
            assertThat(h.spring.getBeanFactory().containsSingleton("marker")).isFalse();
            Event event = event();
            ModuleEventExecutionFrame frame = h.bind(event);
            h.marker().handle(event);
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.frames).containsExactly(frame);
        }
    }

    @Test
    void frameの無い同pool通常taskも実行し受付を解放する() throws Exception {
        try (Harness h = new Harness(false)) {
            CountDownLatch done = new CountDownLatch(1);
            h.pool.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> h.pool.getActiveCount() == 0);
            assertThat(h.pool.availableCapacity()).isEqualTo(2);
            assertThat(h.owner.accepted).hasValue(0);
        }
    }

    @Test
    void 非Mの別poolAsyncにはcaptureを追加しない() throws Exception {
        try (Harness h = new Harness(false)) {
            h.spring.getBean(Ordinary.class).run();
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.ordinaryThreads).allMatch(name -> name.startsWith("ordinary-proof-"));
            Advised proxy = (Advised) h.spring.getBean(Ordinary.class);
            assertThat(List.of(proxy.getAdvisors())).allMatch(advisor ->
                    !advisor.getAdvice().getClass().getName().contains("ModuleEventInvocationCapturePostProcessor"));
        }
    }

    @Test
    void 初回submit未解決中の期限回収と同実体再登録を拒否する() {
        ModuleEventExecutionFrame.Context context = new ModuleEventExecutionFrame.Context();
        Event event = event();
        ModuleEventExecutionFrame first = frame(event);
        context.register(first);
        AtomicInteger cleanup = new AtomicInteger();
        assertThat(context.expire(first, cleanup::incrementAndGet)).isFalse();
        assertThatThrownBy(() -> context.register(frame(event))).isInstanceOf(IllegalStateException.class);
        assertThat(cleanup).hasValue(0);
    }

    @Test
    void 同値でも別実体のeventと異なるtargetを混同しない() {
        ModuleEventExecutionFrame.Context context = new ModuleEventExecutionFrame.Context();
        Event first = event();
        Event equal = new Event(first.operationId(), first.occurredAt());
        ModuleEventExecutionFrame a = frame(first);
        ModuleEventExecutionFrame b = frame(equal);
        ModuleEventExecutionFrame c = new ModuleEventExecutionFrame(first, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "proof-second", false);
        context.register(a);
        context.register(b);
        context.register(c);
        assertThat(context.capture(first, "proof-marker")).containsSame(a);
        assertThat(context.capture(equal, "proof-marker")).containsSame(b);
        assertThat(context.capture(first, "proof-second")).containsSame(c);
    }

    @Test
    void 旧世代cleanupは新世代trackerを解除しない() {
        ModuleEventExecutionFrame.Context context = new ModuleEventExecutionFrame.Context();
        Event event = event();
        ModuleEventExecutionFrame old = frame(event);
        context.register(old);
        context.capture(event, old.listenerId());
        AtomicInteger cleanup = new AtomicInteger();
        assertThat(context.expire(old, cleanup::incrementAndGet)).isTrue();
        ModuleEventExecutionFrame current = frame(event);
        context.register(current);
        assertThat(context.release(old, cleanup::incrementAndGet)).isFalse();
        assertThat(cleanup).hasValue(1);
        assertThat(context.capture(event, current.listenerId())).containsSame(current);
    }

    @Test
    void worker再利用で前taskのframeを残さない() throws Exception {
        try (Harness h = new Harness(false)) {
            Event event = event();
            h.bind(event);
            h.marker().handle(event);
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Object> observed = new AtomicReference<>("unset");
            h.pool.submit(() -> observed.set(h.context.execution().orElse(null))).get(5, TimeUnit.SECONDS);
            assertThat(observed).hasValue(null);
            assertThat(h.owner.accepted).hasValue(1);
        }
    }

    @Test
    void 全submit経路で受付上限を共有し拒否世代を通知する() throws Exception {
        try (Harness h = new Harness(false)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            h.pool.execute(() -> { running.countDown(); waitFor(unblock); });
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                Event queued = event();
                h.bind(queued);
                h.marker().handle(queued);
                Event rejected = event();
                h.bind(rejected);
                assertThatThrownBy(() -> h.marker().handle(rejected)).isInstanceOf(TaskRejectedException.class);
                assertThat(h.owner.rejected).hasValue(1);
                assertThat(h.owner.accepted).hasValue(0);
                assertThat(h.pool.availableCapacity()).isZero();
                assertThatThrownBy(() -> h.pool.submit(() -> {})).isInstanceOf(TaskRejectedException.class);
                assertThatThrownBy(() -> h.pool.submit(() -> 1)).isInstanceOf(TaskRejectedException.class);
            } finally {
                unblock.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(h.pool.availableCapacity()).isEqualTo(2));
        }
    }

    @Test
    void 古いqueuedTaskは新しい同実体世代を拾わず業務開始もしない() throws Exception {
        try (Harness h = new Harness(false)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            h.pool.execute(() -> { running.countDown(); waitFor(unblock); });
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                Event event = event();
                ModuleEventExecutionFrame old = h.bind(event);
                h.marker().handle(event);
                assertThat(h.context.expire(old, () -> {})).isTrue();
                ModuleEventExecutionFrame current = h.bind(event);
                h.owner.onlyToken.set(current.token());
                unblock.countDown();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(h.owner.finished).hasValue(1));
                assertThat(h.probe.frames).isEmpty();
                assertThat(h.owner.accepted).hasValue(0);
                h.marker().handle(event);
                assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(h.probe.frames).containsExactly(current);
                assertThat(h.owner.accepted).hasValue(1);
            } finally {
                unblock.countDown();
            }
        }
    }

    @Test
    void afterCompletionが遅れても未投入の元frameを失わない() throws Exception {
        try (Harness h = new Harness(false)) {
            Event event = event();
            ModuleEventExecutionFrame frame = h.bind(event);
            CountDownLatch completion = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread publisher = new Thread(() -> {
                try {
                    new TransactionTemplate(new LocalTransactionManager()).executeWithoutResult(status ->
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void afterCompletion(int state) {
                                    completion.countDown(); waitFor(release); h.marker().handle(event);
                                }
                            }));
                } catch (Throwable failure) {
                    error.set(failure);
                }
            }, "module-publisher-proof");
            publisher.start();
            try {
                assertThat(completion.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(h.context.expire(frame, () -> {})).isFalse();
                assertThatThrownBy(() -> h.bind(event)).isInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown(); publisher.join(5000);
            }
            assertThat(publisher.isAlive()).isFalse();
            assertThat(error).hasValue(null);
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.frames).containsExactly(frame);
        }
    }

    @Test
    void tracker解除と世代所有の解放を同じ同期境界で完了する() throws Exception {
        ModuleEventExecutionFrame.Context context = new ModuleEventExecutionFrame.Context();
        Event event = event();
        ModuleEventExecutionFrame old = frame(event);
        context.register(old);
        context.capture(event, old.listenerId());
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch cleanupRelease = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch replacementDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread cleanup = new Thread(() -> context.release(old, () -> {
            cleanupEntered.countDown(); waitFor(cleanupRelease);
        }));
        Thread replacement = new Thread(() -> {
            replacementStarted.countDown();
            try { context.register(frame(event)); } catch (Throwable error) { failure.set(error); }
            replacementDone.countDown();
        });
        cleanup.start();
        try {
            assertThat(cleanupEntered.await(5, TimeUnit.SECONDS)).isTrue();
            replacement.start();
            assertThat(replacementStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(replacementDone.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            cleanupRelease.countDown(); cleanup.join(5000);
            if (replacement.getState() != Thread.State.NEW) replacement.join(5000);
        }
        assertThat(replacementDone.getCount()).isZero();
        assertThat(failure).hasValue(null);
    }

    @Test
    void queuedFuture取消は入口受理せず世代と受付を解放する() throws Exception {
        try (Harness h = new Harness(false)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            h.pool.execute(() -> { running.countDown(); waitFor(unblock); });
            Event event = event();
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                ModuleEventExecutionFrame old = h.bind(event);
                assertThat(h.context.capture(event, old.listenerId())).containsSame(old);
                try (var ignored = h.context.submitting(old)) {
                    var future = h.pool.submit(() -> h.probe.order.add("cancelled-body"));
                    assertThat(future.cancel(false)).isTrue();
                }
            } finally {
                unblock.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> h.pool.getActiveCount() == 0
                    && h.pool.getThreadPoolExecutor().getQueue().isEmpty());
            assertThat(h.owner.accepted).hasValue(0);
            assertThat(h.owner.rejected).hasValue(1);
            assertThat(h.owner.trackerCleanup).hasValue(1);
            assertThat(h.probe.order).isEmpty();
            assertThat(h.pool.availableCapacity()).isEqualTo(2);
            ModuleEventExecutionFrame current = h.bind(event);
            h.marker().handle(event);
            assertThat(h.probe.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.frames).containsExactly(current);
        }
    }

    @Test
    void shutdownで未実行queueを返す時も世代と受付を一度だけ解放する() throws Exception {
        try (Harness h = new Harness(false)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            h.pool.execute(() -> {
                running.countDown();
                boolean interrupted = false;
                while (unblock.getCount() > 0) {
                    try { unblock.await(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            });
            Event event = event();
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                h.bind(event);
                h.marker().handle(event);
                h.pool.shutdown();
                assertThat(h.owner.accepted).hasValue(0);
                assertThat(h.owner.rejected).hasValue(1);
                assertThat(h.owner.trackerCleanup).hasValue(1);
                assertThat(h.pool.availableCapacity()).isEqualTo(1);
                // 終了queueの旧登録が強参照のまま残っていないことを再登録で確認する。
                h.bind(event);
            } finally {
                unblock.countDown();
            }
            assertThat(h.pool.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.pool.availableCapacity()).isEqualTo(2);
            assertThat(h.owner.rejected).hasValue(1);
            assertThat(h.probe.frames).isEmpty();
        }
    }

    static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("mini試練の解放待ちが期限切れ");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
        }
    }

    /** DBは使わずSpring自身の同期callback順序だけを観測する検体。 */
    static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    static Event event() { return new Event(UUID.randomUUID(), LocalDateTime.now()); }
    static ModuleEventExecutionFrame frame(Event event) {
        return new ModuleEventExecutionFrame(event, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "proof-marker", false);
    }
    record Event(UUID operationId, LocalDateTime occurredAt) implements ReliableEvent {}

    static class Probe {
        final CountDownLatch done = new CountDownLatch(1);
        final List<ModuleEventExecutionFrame> frames = new CopyOnWriteArrayList<>();
        final List<String> order = new CopyOnWriteArrayList<>();
        final List<String> ordinaryThreads = new CopyOnWriteArrayList<>();
    }

    static class Owner implements ModuleEventExecutionFrame.Owner {
        final Probe probe;
        final ModuleEventExecutionFrame.Context context;
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicReference<UUID> onlyToken = new AtomicReference<>();
        final AtomicInteger trackerCleanup = new AtomicInteger();
        Owner(Probe probe, ModuleEventExecutionFrame.Context context) { this.probe = probe; this.context = context; }
        @Override public boolean admit(ModuleEventExecutionFrame frame) {
            if (onlyToken.get() != null && !onlyToken.get().equals(frame.token())) return false;
            accepted.incrementAndGet(); probe.order.add("accept"); return true;
        }
        @Override public void rejected(ModuleEventExecutionFrame frame) {
            context.release(frame, trackerCleanup::incrementAndGet); rejected.incrementAndGet();
        }
        @Override public void finished(ModuleEventExecutionFrame frame) {
            context.release(frame, trackerCleanup::incrementAndGet); finished.incrementAndGet();
        }
    }

    static class Marker {
        final ModuleEventExecutionFrame.Context context;
        final Probe probe;
        Marker(ModuleEventExecutionFrame.Context context, Probe probe) { this.context = context; this.probe = probe; }
        @ModuleEventListener(id = "proof-marker")
        public void handle(Event event) {
            context.execution().ifPresent(probe.frames::add);
            probe.order.add("body");
            probe.done.countDown();
        }
    }

    static class Ordinary {
        final Probe probe;
        Ordinary(Probe probe) { this.probe = probe; }
        @Async("ordinary-pool") public void run() {
            probe.ordinaryThreads.add(Thread.currentThread().getName()); probe.done.countDown();
        }
    }

    static class Harness implements AutoCloseable {
        final ModuleEventExecutionFrame.Context context = new ModuleEventExecutionFrame.Context();
        final Probe probe = new Probe();
        final Owner owner = new Owner(probe, context);
        final ModuleEventTaskExecutor pool = new ModuleEventTaskExecutor(context, owner, 2, task -> task);
        final ThreadPoolTaskExecutor ordinary = new ThreadPoolTaskExecutor();
        final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        Harness(boolean lazy) {
            pool.setCorePoolSize(1); pool.setMaxPoolSize(1); pool.setQueueCapacity(2);
            pool.setThreadNamePrefix("module-proof-"); pool.initialize();
            ordinary.setCorePoolSize(1); ordinary.setThreadNamePrefix("ordinary-proof-"); ordinary.initialize();
            spring.getBeanFactory().registerSingleton("module-event-pool", pool);
            spring.getBeanFactory().registerSingleton("ordinary-pool", ordinary);
            spring.registerBean("standardAsync", AsyncAnnotationBeanPostProcessor.class);
            spring.registerBean(ModuleEventInvocationCapturePostProcessor.class, () ->
                    new ModuleEventInvocationCapturePostProcessor(context));
            spring.registerBean("marker", Marker.class, () -> new Marker(context, probe), definition ->
                    definition.setLazyInit(lazy));
            spring.registerBean(Ordinary.class, () -> new Ordinary(probe));
            spring.refresh();
        }
        Marker marker() { return spring.getBean(Marker.class); }
        ModuleEventExecutionFrame bind(Event event) {
            ModuleEventExecutionFrame frame = frame(event); context.register(frame); return frame;
        }
        @Override public void close() { spring.close(); pool.shutdown(); ordinary.shutdown(); }
    }
}
