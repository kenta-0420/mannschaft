package com.mannschaft.app.config.websocket;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-261110-2011 正式試練。実Spring Beanのscheduler破棄後の予約拒否/late予約とdelegate close到達を検査。
 * schedulerはmockしない。transport境界のみ制御し、実TCP停止検証の代替とはしない。
 * 単独scheduler.stopのpauseや、実AppContext.stop/closeの資格とは分ける。
 */
class ProtocolCloseSchedulerLifecycleTest {
    private static final long BOUND_SECONDS = 5;
    private static final int SEND_LIMIT = 10_000;
    private static final int BUFFER_LIMIT = 64 * 1024;

    @Test
    void 実schedulerBean停止後の予約拒否を実際に通してcloseへ到達する() throws Exception {
        try (GenericApplicationContext context = schedulerContext()) {
            CountingScheduler scheduler = context.getBean(CountingScheduler.class);
            scheduler.destroy();
            assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
            assertThatThrownBy(() -> scheduler.schedule(() -> { }, Instant.now()))
                    .isInstanceOf(RejectedExecutionException.class);
            int before = scheduler.attempts.get();
            Transport fixture = new Transport(scheduler);
            Thread sender = fixture.sender();
            sender.start();
            try {
                assertThat(fixture.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
                fixture.session.close(CloseStatus.PROTOCOL_ERROR);
                assertThat(scheduler.attempts).as("停止した実Beanへの予約試行を省略していない").hasValue(before + 1);
                assertThat(fixture.closed).hasValue(1);
            } finally {
                fixture.release.countDown();
                sender.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            }
            fixture.assertFinished(sender);
            assertThat(fixture.closed).hasValue(1);
        }
    }

    @Test
    void 実scheduler登録が遅れ停止した場合も予約拒否とcloseを確認する() throws Exception {
        try (GenericApplicationContext context = schedulerContext()) {
            CountingScheduler scheduler = context.getBean(CountingScheduler.class);
            scheduler.blockRegistration = true;
            Transport fixture = new Transport(scheduler);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread sender = fixture.sender();
            Thread closer = new Thread(() -> {
                try { fixture.session.close(CloseStatus.PROTOCOL_ERROR); }
                catch (Throwable failure) { closeFailure.set(failure); }
            }, "formal-late-scheduler-close");
            sender.start();
            try {
                assertThat(fixture.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
                closer.start();
                assertThat(scheduler.registrationEntered.await(BOUND_SECONDS, TimeUnit.SECONDS))
                        .as("実scheduler予約の入口に到達したことをassert、未呼出をNPEやgreenにしない").isTrue();
                scheduler.destroy();
                assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
                scheduler.releaseRegistration.countDown();
                closer.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
                assertThat(closer.isAlive()).isFalse();
                assertThat(closeFailure.get()).isNull();
                assertThat(scheduler.rejections).hasValue(1);
                assertThat(fixture.closed).hasValue(1);
            } finally {
                scheduler.releaseRegistration.countDown();
                fixture.release.countDown();
                if (closer.getState() != Thread.State.NEW) closer.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
                sender.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            }
            fixture.assertFinished(sender);
            assertThat(closer.isAlive()).isFalse();
            assertThat(fixture.closed).hasValue(1);
        }
    }

    private static GenericApplicationContext schedulerContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("messageBrokerTaskScheduler", CountingScheduler.class);
        context.refresh();
        return context;
    }

    /** 生成・停止・拒否処理は実ThreadPoolTaskSchedulerへ委譲し、発火回数だけを観測する。 */
    public static class CountingScheduler extends ThreadPoolTaskScheduler {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger rejections = new AtomicInteger();
        final CountDownLatch registrationEntered = new CountDownLatch(1);
        final CountDownLatch releaseRegistration = new CountDownLatch(1);
        volatile boolean blockRegistration;
        public CountingScheduler() { setPoolSize(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            attempts.incrementAndGet();
            if (blockRegistration) {
                registrationEntered.countDown();
                try {
                    if (!releaseRegistration.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture releaseRegistration missing");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture registration interrupted", failure);
                }
            }
            try { return super.schedule(task, startTime); }
            catch (RejectedExecutionException rejection) { rejections.incrementAndGet(); throw rejection; }
        }
    }

    private static final class Transport {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final DeferredProtocolCloseWebSocketSession session;
        Transport(CountingScheduler scheduler) throws Exception {
            WebSocketSession delegate = mock(WebSocketSession.class);
            when(delegate.getId()).thenReturn("formal-scheduler-stop");
            when(delegate.isOpen()).thenReturn(true);
            when(delegate.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            doAnswer(call -> {
                entered.countDown();
                if (!release.await(BOUND_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("fixture send release missing");
                return null;
            }).when(delegate).sendMessage(any());
            doAnswer(call -> { closed.incrementAndGet(); return null; }).when(delegate).close(any(CloseStatus.class));
            session = new DeferredProtocolCloseWebSocketSession(delegate, SEND_LIMIT, BUFFER_LIMIT, scheduler);
        }
        Thread sender() {
            return new Thread(() -> {
                try { session.sendMessage(new TextMessage("CONNECTED")); }
                catch (Throwable error) { failure.set(error); }
            }, "formal-scheduler-stop-sender");
        }
        void assertFinished(Thread sender) {
            assertThat(sender.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
        }
    }
}
