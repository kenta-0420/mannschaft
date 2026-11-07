package com.mannschaft.app.timeline.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.dto.TimelineContentFingerprint;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 本物の有限executorで飽和と終了境界を検証する。DB保存やHTTP性能の証明には使用しない。
 * 1秒の期限は飽和中のproducer復帰を検出するもので、SLOの測定値ではない。
 */
class TimelineRanchCaptureQueueTest {
    private static final int WAIT_SECONDS = 10;
    private final UserRewardDeliveryGuard users = mock(UserRewardDeliveryGuard.class);
    private final TimelineRanchTransportWriter writer = mock(TimelineRanchTransportWriter.class);
    private final TimelineRanchCaptureTelemetry telemetry = mock(TimelineRanchCaptureTelemetry.class);

    @Test
    void 飽和時の第四受付は呼出threadで配送せず短い期限で戻る() throws Exception {
        var first = capture(1L);
        var pending = capture(2L);
        var secondWorker = capture(3L);
        var rejected = capture(4L);
        var release = new CountDownLatch(1);
        var entered = blockDeliveries(release);
        var queue = new TimelineRanchCaptureQueue(users, writer, telemetry, 1);
        var executor = executor(queue);
        var caller = Executors.newSingleThreadExecutor();
        var callerThread = new AtomicReference<Thread>();

        try {
            saturate(queue, executor, entered, first, pending, secondWorker);
            caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                queue.offer(rejected);
            }).get(1, TimeUnit.SECONDS);

            assertThat(entered.threads).doesNotContain(callerThread.get());
            assertThat(entered.userIds).containsExactlyInAnyOrder(1L, 3L);
            assertThat(executor.getQueue()).hasSize(1);
            verify(telemetry).lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_REJECTED);
            verifyNoInteractions(writer);
            release.countDown();
            queue.close();
            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(writer).accept(first);
            verify(writer).accept(pending);
            verify(writer).accept(secondWorker);
            verify(writer, never()).accept(rejected);
            verifyNoMoreInteractions(writer);
        } finally {
            release.countDown();
            queue.close();
            stop(executor, caller);
        }
    }

    @Test
    void 終了後の受付は例外を漏らさず拒否され配送しない() throws Exception {
        var queue = new TimelineRanchCaptureQueue(users, writer, telemetry, 1);
        var executor = executor(queue);
        try {
            queue.close();

            queue.offer(capture(4L));

            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(telemetry).lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_REJECTED);
            verifyNoInteractions(users, writer);
        } finally {
            queue.close();
            stop(executor);
        }
    }

    @Test
    void 終了前に受付済みの三件をdrainし新instanceは新しい捕捉を受付する() throws Exception {
        var first = capture(1L);
        var pending = capture(2L);
        var secondWorker = capture(3L);
        var fresh = capture(5L);
        var release = new CountDownLatch(1);
        var entered = blockDeliveries(release);
        var oldQueue = new TimelineRanchCaptureQueue(users, writer, telemetry, 1);
        var oldExecutor = executor(oldQueue);
        var newQueue = new TimelineRanchCaptureQueue(users, writer, telemetry, 1);
        var newExecutor = executor(newQueue);
        try {
            saturate(oldQueue, oldExecutor, entered, first, pending, secondWorker);

            oldQueue.close();
            assertThat(oldExecutor.isShutdown()).isTrue();
            release.countDown();
            assertThat(oldExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(writer).accept(first);
            verify(writer).accept(pending);
            verify(writer).accept(secondWorker);

            // 新instanceへの新規受付だけを確認する。旧jobの永続再送はこのqueueの契約に含めない。
            newQueue.offer(fresh);
            newQueue.close();
            assertThat(newExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(writer).accept(fresh);
            verifyNoMoreInteractions(writer);
            verifyNoInteractions(telemetry);
        } finally {
            release.countDown();
            oldQueue.close();
            newQueue.close();
            stop(oldExecutor, newExecutor);
        }
    }

    private DeliveryEntries blockDeliveries(CountDownLatch release) {
        var entries = new DeliveryEntries();
        doAnswer(invocation -> {
            entries.userIds.add(invocation.getArgument(0));
            entries.threads.add(Thread.currentThread());
            entries.first.countDown();
            entries.both.countDown();
            assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("配送workerの解放期限").isTrue();
            Function<DeliveryUserState, Boolean> operation = invocation.getArgument(1);
            return operation.apply(new DeliveryUserState(DeliveryUserState.Lifecycle.ACTIVE, null));
        }).when(users).withLockedDeliveryUser(anyLong(), any());
        return entries;
    }

    private void saturate(TimelineRanchCaptureQueue queue, ThreadPoolExecutor executor, DeliveryEntries entries,
            TimelineRanchCapture first, TimelineRanchCapture pending, TimelineRanchCapture secondWorker) throws Exception {
        queue.offer(first);
        assertThat(entries.first.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // core1を止めた状態でpendingを満杯にしてから第2workerを実際に起動する。
        queue.offer(pending);
        assertThat(executor.getQueue()).hasSize(1);
        queue.offer(secondWorker);
        assertThat(entries.both.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.getActiveCount()).isEqualTo(2);
        assertThat(executor.getQueue()).hasSize(1);
    }

    private ThreadPoolExecutor executor(TimelineRanchCaptureQueue queue) {
        var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(queue, "executor");
        assertThat(executor).isNotNull();
        return executor;
    }

    private void stop(ExecutorService... executors) throws InterruptedException {
        for (var executor : executors) executor.shutdownNow();
        boolean terminated = true;
        for (var executor : executors) terminated &= executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(terminated).as("専有threadの終了").isTrue();
    }

    private TimelineRanchCapture capture(long userId) {
        var payload = new TimelineRanchRewardPayload(new UUID(0L, userId), 1,
                RanchRewardSourceType.TIMELINE_ORIGINAL, RanchRewardEnvelope.IdType.LONG, Long.toString(userId),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, userId, null, userId, userId,
                Instant.parse("2026-10-05T00:00:00Z"), RanchRewardEnvelope.Origin.ORIGINAL,
                new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, true));
        return new TimelineRanchCapture(payload, new TimelineContentFingerprint(TimelineContentFingerprintService.VERSION,
                LocalDate.of(2026, 10, 5), "test-key", "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    /** latchで確定した受信記録。executorの状態を書き換えない。 */
    private static class DeliveryEntries {
        private final CountDownLatch first = new CountDownLatch(1);
        private final CountDownLatch both = new CountDownLatch(2);
        private final Set<Long> userIds = ConcurrentHashMap.newKeySet();
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    }
}
