package com.mannschaft.app.config.websocket;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CMP-261110-2011 正式試練。pool/sleepなし、schedulerは手動発火。未実行。 */
class DeferredProtocolCloseWebSocketSessionTest {
    private static final long BOUND_SECONDS = 5;
    private static final int SEND_LIMIT = 10_000;
    private static final int BUFFER_LIMIT = 64 * 1024;

    @Test void 期限予約は一回でpending後新規送信をdropする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        f.inFlight(() -> {
            f.session.close(CloseStatus.PROTOCOL_ERROR);
            f.session.close(CloseStatus.PROTOCOL_ERROR);
            f.session.sendMessage(new TextMessage("LATE"));
            assertThat(f.events).containsExactly("SEND CONNECTED");
            verify(f.scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        });
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        verify(f.future).cancel(false);
        f.assertOneClose();
    }

    @Test void 期限発火とlastActive再試行でも実close一回() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        f.inFlight(() -> {
            f.session.close(CloseStatus.PROTOCOL_ERROR);
            assertThat(f.deadline.get()).as("期限予約の成立が必要。null derefをredにしない" ).isNotNull();
            f.deadline.get().run();
            assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        });
        f.assertOneClose();
    }

    @Test void 期限closeLock空振り後もlastActiveが再試行する() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        // v6.2.17のprivate lockに限定した機序test。製品コードにreflectionを入れない。
        var field = ConcurrentWebSocketSessionDecorator.class.getDeclaredField("closeLock");
        field.setAccessible(true);
        Lock closeLock = (Lock) field.get(f.session);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch unlock = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            closeLock.lock();
            try {
                held.countDown();
                if (!unlock.await(BOUND_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("fixture unlock missing");
            } catch (Throwable failure) { holderFailure.set(failure); }
            finally { closeLock.unlock(); }
        }, "private-close-lock-holder");
        holder.start();
        try {
            assertThat(held.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            f.inFlight(() -> {
                f.session.close(CloseStatus.PROTOCOL_ERROR);
                assertThat(f.deadline.get()).as("期限予約の成立が必要。null derefをredにしない" ).isNotNull();
            f.deadline.get().run();
                assertThat(f.events).containsExactly("SEND CONNECTED");
                unlock.countDown();
                holder.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
                assertThat(holder.isAlive()).isFalse();
            });
        } finally {
            unlock.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
        }
        assertThat(holderFailure.get()).isNull();
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        f.assertOneClose();
    }

    @Test void 他statusを即時優先し遅れた期限は上書きしない() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        f.inFlight(() -> {
            f.session.close(CloseStatus.PROTOCOL_ERROR);
            Runnable stale = f.deadline.get();
            assertThat(stale).as("遅延期限が予約されたこと" ).isNotNull();
            f.session.close(CloseStatus.NORMAL);
            stale.run();
            assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1000");
        });
        verify(f.future).cancel(false);
        f.assertOneClose();
    }

    @Test void 登録前他closeならlateFutureをcancelFalseする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            f.session.close(CloseStatus.NORMAL); return f.future;
        });
        f.inFlight(() -> f.session.close(CloseStatus.PROTOCOL_ERROR));
        verify(f.future).cancel(false);
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1000");
        f.assertOneClose();
    }

    @Test void 登録前期限再入ならlateFutureをcancelFalseする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            i.<Runnable>getArgument(0).run(); return f.future;
        });
        f.inFlight(() -> f.session.close(CloseStatus.PROTOCOL_ERROR));
        verify(f.future).cancel(false);
        f.assertOneClose();
    }

    @Test void scheduler拒否は即時fallbackする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new RejectedExecutionException("fixture rejected"));
        f.inFlight(() -> {
            f.session.close(CloseStatus.PROTOCOL_ERROR);
            assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        });
        verify(f.scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        f.assertOneClose();
    }

    @Test void scheduler停止は即時fallbackする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("fixture stopped"));
        f.inFlight(() -> f.session.close(CloseStatus.PROTOCOL_ERROR));
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        verify(f.scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        f.assertOneClose();
    }

    @Test void schedulerNullFutureは即時fallbackする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(null);
        f.inFlight(() -> f.session.close(CloseStatus.PROTOCOL_ERROR));
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        verify(f.scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        f.assertOneClose();
    }

    @Test void schedulerNullは即時fallbackする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        f.session = new DeferredProtocolCloseWebSocketSession(f.delegate, SEND_LIMIT, BUFFER_LIMIT, null);
        f.inFlight(() -> f.session.close(CloseStatus.PROTOCOL_ERROR));
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        f.assertOneClose();
    }

    @Test void limitZero負値はbase同様即時でlimit判定を保つ() throws Exception {
        for (int limit : new int[] {0, -1}) {
            Fixture f = new Fixture(limit, BUFFER_LIMIT, false);
            // 0の経過ms境界を固定し、baseの > limit 判定を決定的に観測する。
            f.session = new DeferredProtocolCloseWebSocketSession(f.delegate, limit, BUFFER_LIMIT, f.scheduler) {
                @Override public long getTimeSinceSendStarted() { return 0; }
            };
            int code = limit == 0 ? 1002 : CloseStatus.SESSION_NOT_RELIABLE.getCode();
            f.inFlight(() -> {
                f.session.close(CloseStatus.PROTOCOL_ERROR);
                assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE " + code);
            });
            verifyNoInteractions(f.scheduler);
            f.assertOneClose();
        }
    }

    @Test void delegateClose再入でも実close一回() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        doAnswer(i -> {
            f.events.add("CLOSE " + i.<CloseStatus>getArgument(0).getCode());
            f.session.close(CloseStatus.PROTOCOL_ERROR); return null;
        }).when(f.delegate).close(any(CloseStatus.class));
        f.session.close(CloseStatus.PROTOCOL_ERROR);
        assertThat(f.events).containsExactly("CLOSE 1002");
        f.assertOneClose();
    }

    @Test void sendIOExceptionを主原因にcloseIOExceptionをsuppressedへ残す() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        IOException sendFailure = new IOException("fixture send failed");
        IOException closeFailure = new IOException("fixture close failed");
        doAnswer(i -> { f.session.close(CloseStatus.PROTOCOL_ERROR); throw sendFailure; })
                .when(f.delegate).sendMessage(any());
        doThrow(closeFailure).when(f.delegate).close(any(CloseStatus.class));
        assertThatThrownBy(() -> f.session.sendMessage(new TextMessage("CONNECTED"))).isSameAs(sendFailure);
        assertThat(sendFailure.getSuppressed()).containsExactly(closeFailure);
        f.assertOneClose();
    }

    @Test void baseBufferLimitのSessionNotReliableを維持する() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, 1, false);
        f.inFlight(() -> {
            assertThatThrownBy(() -> f.session.sendMessage(new TextMessage("BUFFER")))
                    .isInstanceOf(SessionLimitExceededException.class);
            f.session.close(CloseStatus.NORMAL);
        });
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE " + CloseStatus.SESSION_NOT_RELIABLE.getCode());
        f.assertOneClose();
    }

    @Test void baseTimeLimitをsleepなしで維持する() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, true);
        f.inFlight(() -> {
            assertThatThrownBy(() -> f.session.sendMessage(new TextMessage("BUFFER")))
                    .isInstanceOf(SessionLimitExceededException.class);
            f.session.close(CloseStatus.NORMAL);
        });
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE " + CloseStatus.SESSION_NOT_RELIABLE.getCode());
        f.assertOneClose();
    }

    @Test void callbackとdelegateIdentityを維持する() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        List<String> callbacks = new CopyOnWriteArrayList<>();
        f.session.setMessageCallback(m -> callbacks.add(((TextMessage)m).getPayload()));
        f.session.sendMessage(new TextMessage("PAYLOAD"));
        assertThat(callbacks).containsExactly("PAYLOAD");
        assertThat(f.session.getDelegate()).isSameAs(f.delegate);
    }

    @Test void callback内close再入でも送信完了後に閉じ期限を取消す() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        f.session.setMessageCallback(message -> {
            try { f.session.close(CloseStatus.PROTOCOL_ERROR); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        f.session.sendMessage(new TextMessage("PAYLOAD"));
        assertThat(f.events).containsExactly("SEND PAYLOAD", "CLOSE 1002");
        verify(f.scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        verify(f.future).cancel(false);
        f.assertOneClose();
    }

    @Test void schedule中に別threadでdrain済みならlateFutureをcancelFalseする() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        AtomicReference<Throwable> senderFailure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try { f.session.sendMessage(new TextMessage("CONNECTED")); }
            catch (Throwable failure) { senderFailure.set(failure); }
        }, "private-late-registration-sender");
        when(f.scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            // schedule呼出中にsenderがfinishSendへ入れる必要がある。monitor内scheduleなら失敗する。
            f.release.countDown();
            sender.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            assertThat(sender.isAlive()).as("schedule復帰前に別threadのdrainが完了する").isFalse();
            return f.future;
        });
        sender.start();
        try {
            assertThat(f.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            f.session.close(CloseStatus.PROTOCOL_ERROR);
        } finally {
            f.release.countDown();
            sender.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
        }
        assertThat(sender.isAlive()).isFalse();
        assertThat(senderFailure.get()).isNull();
        assertThat(f.events).containsExactly("SEND CONNECTED", "CLOSE 1002");
        verify(f.future).cancel(false);
        f.assertOneClose();
    }

    @Test void sendIOExceptionをcloseRuntimeExceptionで上書きしない() throws Exception {
        Fixture f = new Fixture(SEND_LIMIT, BUFFER_LIMIT, false);
        IOException sendFailure = new IOException("fixture primary send failed");
        IllegalStateException closeFailure = new IllegalStateException("fixture secondary close failed");
        doAnswer(i -> { f.session.close(CloseStatus.PROTOCOL_ERROR); throw sendFailure; })
                .when(f.delegate).sendMessage(any());
        doThrow(closeFailure).when(f.delegate).close(any(CloseStatus.class));
        assertThatThrownBy(() -> f.session.sendMessage(new TextMessage("CONNECTED"))).isSameAs(sendFailure);
        assertThat(sendFailure.getSuppressed()).containsExactly(closeFailure);
        f.assertOneClose();
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static final class Fixture {
        final WebSocketSession delegate = mock(WebSocketSession.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        final AtomicReference<Runnable> deadline = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        DeferredProtocolCloseWebSocketSession session;
        Fixture(int limit, int buffer, boolean forceTimeLimit) throws Exception {
            when(delegate.getId()).thenReturn("private-prototype");
            when(delegate.isOpen()).thenReturn(true);
            when(delegate.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
                deadline.set(i.getArgument(0)); return future;
            });
            doAnswer(i -> {
                String payload = i.<TextMessage>getArgument(0).getPayload();
                events.add("SEND " + payload);
                if (payload.equals("CONNECTED")) {
                    entered.countDown();
                    if (!release.await(BOUND_SECONDS, TimeUnit.SECONDS)) throw new IOException("fixture release missing");
                }
                return null;
            }).when(delegate).sendMessage(any());
            doAnswer(i -> { events.add("CLOSE " + i.<CloseStatus>getArgument(0).getCode()); return null; })
                    .when(delegate).close(any(CloseStatus.class));
            session = forceTimeLimit ? new DeferredProtocolCloseWebSocketSession(delegate, limit, buffer, scheduler) {
                @Override public long getTimeSinceSendStarted() { return SEND_LIMIT + 1L; }
            } : new DeferredProtocolCloseWebSocketSession(delegate, limit, buffer, scheduler);
        }
        void inFlight(CheckedAction action) throws Exception {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try { session.sendMessage(new TextMessage("CONNECTED")); }
                catch (Throwable error) { failure.set(error); }
            }, "private-prototype-sender");
            sender.start();
            try {
                assertThat(entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
                action.run();
            } finally {
                release.countDown();
                sender.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            }
            assertThat(sender.isAlive()).as("背景sender残存なし").isFalse();
            assertThat(failure.get()).as("背景sender失敗を隠さない").isNull();
        }
        void assertOneClose() throws IOException { verify(delegate, times(1)).close(any(CloseStatus.class)); }
    }
}
