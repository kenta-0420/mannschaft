package com.mannschaft.app.auth.mfa;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMP-261012-0910 試練用: 「先行 TX が書き込みを終えたがまだ commit していない」瞬間に後続処理を別スレッドで走らせ、
 * 競合の順序を決定的に固定する道具。
 *
 * <h2>なぜ必要か</h2>
 * <p>開始点だけを揃えた並列実行では、DB の行ロックや読取りビューが絡む競合が起きる順序に固定できず、修正前の実装でも
 * たまたま緑になりうる。本クラスは先行 TX が発行するドメインイベント（{@code DomainEventPublisher#publish} は
 * Spring の同期イベント。先行 TX の内側・commit 前に届く）を合図に、後続処理を<b>別スレッド（別 TX）</b>で開始し、
 * 先行 TX を {@code hold} だけ commit させずに待たせる。</p>
 * <ul>
 *   <li>後続処理が先行 TX の行ロックで待たされる実装（＝直列化されている）なら、後続は {@code hold} 内に終わらない
 *       （{@link #completedWhileHeld()} が false）。先行が commit してから後続が進む。</li>
 *   <li>直列化されていない実装なら、後続は先行の未 commit の書き込みを見ないまま {@code hold} 内に終わる、または
 *       ロック待ちの前に固定した読取りビューのまま進む。いずれも修正前の欠陥がそのまま結果に現れる。</li>
 * </ul>
 *
 * <p>イベントの購読は ApplicationContext のマルチキャスタへ実行時に足し、{@link #close()} で外す
 * （TestContext を増やさないため Bean 定義は使わない）。発火は最初の 1 回だけ（後続処理自身が同じイベントを出しても
 * 再帰しない）。</p>
 */
final class ConcurrentOnEvent implements AutoCloseable {

    private final ApplicationEventMulticaster multicaster;
    private final ApplicationListener<ApplicationEvent> listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fired = new AtomicBoolean();
    private volatile Future<?> follower;
    private volatile boolean completedWhileHeld;

    private ConcurrentOnEvent(ApplicationContext context, Class<?> eventType, Callable<?> followerTask,
                              Duration hold) {
        this.multicaster = context.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                ApplicationEventMulticaster.class);
        // ラムダだとイベント型の総称が解決できないため、型を明示した匿名クラスで全イベントを受ける
        this.listener = new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                onEvent(event, eventType, followerTask, hold);
            }
        };
        multicaster.addApplicationListener(listener);
    }

    private void onEvent(ApplicationEvent event, Class<?> eventType, Callable<?> followerTask, Duration hold) {
        Object payload = event instanceof PayloadApplicationEvent<?> p ? p.getPayload() : event;
        if (!eventType.isInstance(payload) || !fired.compareAndSet(false, true)) {
            return;
        }
        Future<?> f = executor.submit(followerTask);
        follower = f;
        try {
            f.get(hold.toMillis(), TimeUnit.MILLISECONDS);
            completedWhileHeld = true;
        } catch (TimeoutException e) {
            // 後続は先行 TX のロックで待たされている（直列化されている）。先行 TX を commit させる
            completedWhileHeld = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // 後続が先行の commit 前に例外で終わった。結果は follower() から観測する
            completedWhileHeld = true;
        }
    }

    /**
     * 先行 TX 内で {@code eventType} のイベントが発行された瞬間に {@code followerTask} を別スレッドで開始し、
     * 先行 TX を最大 {@code hold} だけ待たせるよう仕掛ける。
     */
    static ConcurrentOnEvent arm(ApplicationContext context, Class<?> eventType, Callable<?> followerTask,
                                 Duration hold) {
        return new ConcurrentOnEvent(context, eventType, followerTask, hold);
    }

    /** 合図のイベントが発行され、後続処理が開始されたか。 */
    boolean fired() {
        return fired.get();
    }

    /** 後続処理が、先行 TX の commit 前（待たせている間）に終わったか。 */
    boolean completedWhileHeld() {
        return completedWhileHeld;
    }

    /** 後続処理の結果（開始されていなければ例外）。 */
    Future<?> follower() {
        if (follower == null) {
            throw new IllegalStateException("合図のイベントが発行されず、後続処理は開始されていない");
        }
        return follower;
    }

    @Override
    public void close() {
        multicaster.removeApplicationListener(listener);
        executor.shutdownNow();
    }
}
