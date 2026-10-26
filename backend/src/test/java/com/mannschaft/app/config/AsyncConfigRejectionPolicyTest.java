package com.mannschaft.app.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Issue #2953 — {@code event-pool} 自己飽和の根治に伴う<b>拒否方針</b>の検証。
 *
 * <p>実際にプールを飽和させ、{@link java.util.concurrent.RejectedExecutionException} が起きる状況を
 * 作った上で、各プールの振る舞いを測る。モックで例外を投げさせる形では
 * 「拒否ハンドラが何をするか」を検証したことにならないため、{@link AsyncConfig} が生成する
 * <b>実物のエグゼキュータ</b>に対してタスクを詰めて飽和させる。</p>
 *
 * <h2>受け入れ条件との対応</h2>
 * <ul>
 *   <li>AC-1（飽和しても通知が消えない）の土台: {@code notification-dispatch-pool} は飽和時に
 *       <b>例外を投げず</b>呼び出し元スレッドでタスクを実行する（CallerRuns）。例外を投げないことが、
 *       {@code NotificationDeliveryRunner#sendOne} の {@code REQUIRES_NEW} を巻き戻さない条件である。</li>
 *   <li>AC-3（投入拒否が silent drop にならない）: 両プールとも拒否時に ERROR ログを残す。</li>
 *   <li>{@code event-pool} は拒否<b>方針</b>（例外送出）を変えない。168 箇所の
 *       {@code @Async("event-pool")} 全体に効くため、可視化だけを足す。</li>
 * </ul>
 *
 * <p>ログ検証はロガーレベルを本テスト自身で明示設定し {@link AfterEach} で復元する
 * （{@link ListAppender} はレベル継承の影響で偽 green になりうるため）。</p>
 */
@DisplayName("Issue #2953 AsyncConfig 拒否方針（実飽和）")
class AsyncConfigRejectionPolicyTest {

    /** {@code AsyncConfig#notificationDispatchPool} のキュー容量（飽和させるために本数を合わせる）。 */
    private static final int DISPATCH_POOL_QUEUE_CAPACITY = 500;

    /** {@code AsyncConfig#eventPoolExecutor} のキュー容量。 */
    private static final int EVENT_POOL_QUEUE_CAPACITY = 100;

    private final AsyncConfig asyncConfig = new AsyncConfig();

    private Logger asyncConfigLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUpLogCapture() {
        asyncConfigLogger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
        originalLevel = asyncConfigLogger.getLevel();
        asyncConfigLogger.setLevel(Level.ERROR);
        appender = new ListAppender<>();
        appender.start();
        asyncConfigLogger.addAppender(appender);
    }

    @AfterEach
    void tearDownLogCapture() {
        asyncConfigLogger.detachAppender(appender);
        appender.stop();
        asyncConfigLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("AC-1: notification-dispatch-pool は飽和しても例外を投げず、タスクを取りこぼさない（CallerRuns）")
    void 通知単発配信プールは飽和時にCallerRunsで実行し例外を投げない() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.notificationDispatchPool();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean executedOnCallerThread = new AtomicBoolean(false);
        String callerThreadName = Thread.currentThread().getName();
        try {
            saturate(executor, DISPATCH_POOL_QUEUE_CAPACITY, release);

            assertThatCode(() -> executor.execute(() ->
                    executedOnCallerThread.set(Thread.currentThread().getName().equals(callerThreadName))))
                    .as("飽和しても例外を投げないこと（投げると sendOne の REQUIRES_NEW が巻き戻り通知行が消える）")
                    .doesNotThrowAnyException();

            assertThat(executedOnCallerThread)
                    .as("捨てずに呼び出し元スレッドで実行されること")
                    .isTrue();

            assertThat(errorMessages())
                    .as("AC-3: 拒否が silent drop にならず ERROR ログで観測できること")
                    .anyMatch(m -> m.contains("notification-dispatch-pool 投入拒否")
                            && m.contains("event=async_task_rejected")
                            && m.contains("policy=caller_runs"));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("AC-3: event-pool は拒否方針（例外送出）を維持したまま、拒否を ERROR ログで可視化する")
    void イベントプールは拒否を例外送出しつつERRORで可視化する() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.eventPoolExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(executor, EVENT_POOL_QUEUE_CAPACITY, release);

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .as("168 箇所に効く拒否方針そのものは変更しない（AbortPolicy 相当を維持）")
                    .isInstanceOf(TaskRejectedException.class);

            assertThat(errorMessages())
                    .as("拒否が silent drop にならず ERROR ログで観測できること")
                    .anyMatch(m -> m.contains("event-pool 投入拒否")
                            && m.contains("event=async_task_rejected"));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /**
     * プールを「次の 1 件が必ず拒否される」状態まで詰める。
     *
     * <p>{@code maxPoolSize} 本のワーカーをラッチで塞ぎ、キューを容量いっぱいまで埋める。
     * ここで拒否が出てしまうと飽和状態を作れないため、詰める段階では拒否が起きないことを確認する。</p>
     */
    private void saturate(ThreadPoolTaskExecutor executor, int queueCapacity, CountDownLatch release)
            throws InterruptedException {
        int capacity = executor.getMaxPoolSize() + queueCapacity;
        CountDownLatch started = new CountDownLatch(executor.getMaxPoolSize());
        for (int i = 0; i < capacity; i++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("全ワーカーが塞がって飽和状態になっていること")
                .isTrue();
        assertThat(errorMessages())
                .as("飽和させる過程では拒否が起きていないこと（測定の前提）")
                .isEmpty();
    }

    private java.util.List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
