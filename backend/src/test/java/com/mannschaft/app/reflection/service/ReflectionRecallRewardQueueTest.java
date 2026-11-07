package com.mannschaft.app.reflection.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 本物の有限executorで飽和と終了境界を検証する。DB保存やHTTP性能の証明には使用しない。
 * 1秒の期限は飽和中のproducer復帰を検出するもので、SLOの測定値ではない。
 */
class ReflectionRecallRewardQueueTest {
    private static final int WAIT_SECONDS = 10;
    private final UserRewardDeliveryGuard users = mock(UserRewardDeliveryGuard.class);
    private final ReflectionRanchTransportWriter writer = mock(ReflectionRanchTransportWriter.class);
    private final ReflectionRanchCaptureTelemetry telemetry = mock(ReflectionRanchCaptureTelemetry.class);

    @Test
    void 飽和時の第四受付は呼出threadで配送せず短い期限で戻る() throws Exception {
        var first = capture(1L);
        var pending = capture(2L);
        var secondWorker = capture(3L);
        var rejected = capture(4L);
        var release = new CountDownLatch(1);
        var entered = blockDeliveries(release);
        var queue = new ReflectionRecallRewardQueue(users, writer, telemetry, 1);
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
            verify(telemetry).lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_REJECTED,
                    RejectedExecutionException.class);
            verifyNoInteractions(writer);
            release.countDown();
            queue.destroy();
            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(writer, never()).accept(rejected);
            verify(users, never()).withLockedDeliveryUser(eq(4L), any());
        } finally {
            release.countDown();
            queue.destroy();
            stop(executor, caller);
        }
    }

    @Test
    void 終了後の受付は例外を漏らさず拒否され配送しない() throws Exception {
        var queue = new ReflectionRecallRewardQueue(users, writer, telemetry, 1);
        var executor = executor(queue);
        try {
            queue.destroy();

            queue.offer(capture(4L));

            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(telemetry).lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_REJECTED,
                    RejectedExecutionException.class);
            verifyNoInteractions(users, writer);
        } finally {
            queue.destroy();
            stop(executor);
        }
    }

    @Test
    void 即時終了は旧pendingを再送せず新instanceは新しいpayloadを受付する() throws Exception {
        var first = capture(1L);
        var pending = capture(2L);
        var secondWorker = capture(3L);
        var rejected = capture(4L);
        var fresh = capture(5L);
        var release = new CountDownLatch(1);
        var entered = blockDeliveries(release);
        var oldQueue = new ReflectionRecallRewardQueue(users, writer, telemetry, 1);
        var oldExecutor = executor(oldQueue);
        var newQueue = new ReflectionRecallRewardQueue(users, writer, telemetry, 1);
        var newExecutor = executor(newQueue);
        var delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivered.countDown();
            return true;
        }).when(writer).accept(fresh);
        try {
            saturate(oldQueue, oldExecutor, entered, first, pending, secondWorker);

            // destroyはshutdownNow。旧pendingのdrainや永続再送を保証するものではない。
            oldQueue.destroy();
            assertThat(oldExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            oldQueue.offer(rejected);
            verify(telemetry).lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_REJECTED,
                    RejectedExecutionException.class);
            assertThat(entered.userIds).containsExactlyInAnyOrder(1L, 3L);
            verify(writer, never()).accept(pending);
            verify(writer, never()).accept(rejected);
            verify(users, never()).withLockedDeliveryUser(eq(2L), any());
            verify(users, never()).withLockedDeliveryUser(eq(4L), any());

            release.countDown();
            newQueue.offer(fresh);
            assertThat(delivered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            newQueue.destroy();
            assertThat(newExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(writer).accept(fresh);
        } finally {
            release.countDown();
            oldQueue.destroy();
            newQueue.destroy();
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
            try {
                assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("配送workerの解放期限").isTrue();
            } catch (InterruptedException interrupted) {
                // shutdownNowの割込を受けた依存処理を終了させ、旧workerを試験後に残さない。
                Thread.currentThread().interrupt();
                return false;
            }
            Function<DeliveryUserState, Boolean> operation = invocation.getArgument(1);
            return operation.apply(new DeliveryUserState(DeliveryUserState.Lifecycle.ACTIVE, null));
        }).when(users).withLockedDeliveryUser(anyLong(), any());
        return entries;
    }

    private void saturate(ReflectionRecallRewardQueue queue, ThreadPoolExecutor executor, DeliveryEntries entries,
            ReflectionRecallRewardPayload first, ReflectionRecallRewardPayload pending,
            ReflectionRecallRewardPayload secondWorker) throws Exception {
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

    private ThreadPoolExecutor executor(ReflectionRecallRewardQueue queue) {
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

    private ReflectionRecallRewardPayload capture(long userId) {
        var payload = new ReflectionRecallRewardPayload(new UUID(0L, userId), 1,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, RanchRewardEnvelope.IdType.LONG, Long.toString(userId),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, userId, null, userId, userId,
                Instant.parse("2026-10-05T00:00:00Z"), RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(new UUID(0L, userId), 1, LocalDate.of(2026, 10, 5), true));
        return payload;
    }

    /** latchで確定した受信記録。executorの状態を書き換えない。 */
    private static class DeliveryEntries {
        private final CountDownLatch first = new CountDownLatch(1);
        private final CountDownLatch both = new CountDownLatch(2);
        private final Set<Long> userIds = ConcurrentHashMap.newKeySet();
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    }
}
