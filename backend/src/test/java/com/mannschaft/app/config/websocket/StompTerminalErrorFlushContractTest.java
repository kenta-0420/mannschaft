package com.mannschaft.app.config.websocket;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import com.mannschaft.app.config.websocket.DeferredProtocolCloseWebSocketSession;
import org.springframework.scheduling.TaskScheduler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-261110-2011 正式試練。未実行であり red 認定ではない。
 * Spring Framework 6.2.17 の実 handler と実 decorator の組合せを検査する。
 * delegate に届いたフレームを観測するため、queue への追加だけでは成功にしない。
 * 実 CI がこのスケジュールだったことや、ネットワーク受信を証明する試験ではない。
 */
class StompTerminalErrorFlushContractTest {

    // fixture の異常終了を有限にする待機上限。製品・既存 IT の timeout は変更しない。
    private static final long FIXTURE_BOUND_SECONDS = 5;

    @Test
    @DisplayName("競合なし: INVALID_DESTINATION の ERROR を実送信してから close1002")
    void 競合なしではERROR送信後に閉じる() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.handler.handleMessageToClient(fixture.session, errorFrame());

        assertThat(fixture.events).containsExactly("SEND ERROR", "CLOSE 1002");
        assertThat(fixture.actualFrames).singleElement()
                .asString().contains("x-error-code:INVALID_DESTINATION");
    }

    @Test
    @DisplayName("契約: CONNECTED の send 復帰前でも ERROR を実送信してから close1002")
    void CONNECTED送信競合でもERROR送信後に閉じる() throws Exception {
        Fixture fixture = new Fixture(true);
        AtomicReference<Throwable> senderFailure = new AtomicReference<>();
        Thread connectedSender = new Thread(() -> {
            try {
                fixture.handler.handleMessageToClient(fixture.session, connectedFrame());
            } catch (Throwable failure) {
                senderFailure.set(failure);
            }
        }, "stomp-connected-contract-draft");
        connectedSender.start();
        try {
            assertThat(fixture.connectedAtDelegate.await(FIXTURE_BOUND_SECONDS, TimeUnit.SECONDS))
                    .as("CONNECTED が delegate へ到達し flushLock を保持している").isTrue();
            // fixture は CONNECTED を実送信一覧へ載せた後、sendMessage の復帰を止める。
            // client が受信済みでも server の flushLock は未解放、という順序を強制する。
            // 現在の 6.2.17 ではこの呼出しは ERROR を buffer に積んで close し戻る。
            fixture.handler.handleMessageToClient(fixture.session, errorFrame());
        } finally {
            fixture.releaseConnected.countDown();
            connectedSender.join(TimeUnit.SECONDS.toMillis(FIXTURE_BOUND_SECONDS));
        }

        assertThat(connectedSender.isAlive()).as("背景 sender が残存しない").isFalse();
        assertThat(senderFailure.get()).as("背景 sender の異常を見落とさない").isNull();
        assertThat(fixture.delegateFailure.get()).as("handler が捕捉した delegate 異常も見落とさない").isNull();
        // 6.2.17 の現実装では SEND ERROR が欠ける想定。未実行につき red 実証ではない。
        assertThat(fixture.events).containsExactly("SEND CONNECTED", "SEND ERROR", "CLOSE 1002");
        assertThat(fixture.actualFrames).anySatisfy(frame ->
                assertThat(frame).startsWith("ERROR\n").contains("x-error-code:INVALID_DESTINATION"));
    }

    private static Message<byte[]> connectedFrame() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setVersion("1.2");
        accessor.setHeartbeat(0, 0);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> errorFrame() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage("invalid destination");
        accessor.setNativeHeader("x-error-code", "INVALID_DESTINATION");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static ConcurrentWebSocketSessionDecorator deferredSession(WebSocketSession delegate,
                                                                       int sendTimeLimit, int bufferLimit) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(mock(ScheduledFuture.class));
        return new DeferredProtocolCloseWebSocketSession(delegate, sendTimeLimit, bufferLimit, scheduler);
    }
    private static final class Fixture {
        final CountDownLatch connectedAtDelegate = new CountDownLatch(1);
        final CountDownLatch releaseConnected = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> actualFrames = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> delegateFailure = new AtomicReference<>();
        final StompSubProtocolHandler handler = new StompSubProtocolHandler();
        final ConcurrentWebSocketSessionDecorator session;

        Fixture(boolean blockConnected) throws Exception {
            WebSocketSession delegate = mock(WebSocketSession.class);
            when(delegate.getId()).thenReturn("stomp-flush-contract-draft");
            when(delegate.isOpen()).thenReturn(true);
            when(delegate.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            // principal は Mockito の既定 null。eventPublisher は登録せず、Spring context を作らない。
            doAnswer(invocation -> {
                TextMessage message = invocation.getArgument(0);
                String frame = message.getPayload();
                actualFrames.add(frame);
                String command = frame.substring(0, frame.indexOf('\n'));
                events.add("SEND " + command);
                if (blockConnected && command.equals("CONNECTED")) {
                    connectedAtDelegate.countDown();
                    try {
                        if (!releaseConnected.await(FIXTURE_BOUND_SECONDS, TimeUnit.SECONDS)) {
                            IOException failure = new IOException("fixture CONNECTED release was not signalled");
                            delegateFailure.set(failure);
                            throw failure;
                        }
                    } catch (InterruptedException failure) {
                        delegateFailure.set(failure);
                        Thread.currentThread().interrupt();
                        throw new IOException("fixture sender interrupted", failure);
                    }
                }
                return null;
            }).when(delegate).sendMessage(any());
            doAnswer(invocation -> {
                CloseStatus status = invocation.getArgument(0);
                events.add("CLOSE " + status.getCode());
                return null;
            }).when(delegate).close(any(CloseStatus.class));
            // limits は既定の製品設定を変える操作ではなく、この fixture の安全な上限。
            session = deferredSession(delegate, 10_000, 64 * 1024);
        }
    }
}
