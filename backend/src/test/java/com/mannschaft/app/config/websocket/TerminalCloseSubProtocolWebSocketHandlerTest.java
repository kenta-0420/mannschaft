package com.mannschaft.app.config.websocket;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SubProtocolHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalCloseSubProtocolWebSocketHandlerTest {
    @Test
    void 同じtransportIdの接続を独立して配送し切断後も残りを維持する() throws Exception {
        MessageChannel inbound = mock(MessageChannel.class);
        SubscribableChannel outbound = mock(SubscribableChannel.class);
        SubProtocolHandler protocol = mock(SubProtocolHandler.class);
        when(protocol.getSupportedProtocols()).thenReturn(List.of("test"));
        TerminalCloseSubProtocolWebSocketHandler handler = new TerminalCloseSubProtocolWebSocketHandler(inbound, outbound, () -> null);
        handler.addProtocolHandler(protocol);
        WebSocketSession first = session();
        WebSocketSession second = session();
        var starts = org.mockito.ArgumentCaptor.forClass(WebSocketSession.class);
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        verify(protocol, times(2)).afterSessionStarted(starts.capture(), any());
        WebSocketSession one = starts.getAllValues().get(0);
        WebSocketSession two = starts.getAllValues().get(1);
        assertThat(one.getId()).isNotEqualTo(two.getId()).isNotEqualTo(first.getId());
        assertThat(WebSocketSessionDecorator.unwrap(one)).isSameAs(first);
        assertThat(one.getAttributes()).isSameAs(first.getAttributes());
        assertThat(one.getPrincipal()).isSameAs(first.getPrincipal());
        handler.handleMessage(first, new TextMessage("first"));
        handler.handleMessage(second, new TextMessage("second"));
        verify(protocol).handleMessageFromClient(eq(one), eq(new TextMessage("first")), any());
        verify(protocol).handleMessageFromClient(eq(two), eq(new TextMessage("second")), any());
        Message<String> outboundOne = MessageBuilder.withPayload("out-one").build();
        Message<String> outboundTwo = MessageBuilder.withPayload("out-two").build();
        when(protocol.resolveSessionId(outboundOne)).thenReturn(one.getId());
        when(protocol.resolveSessionId(outboundTwo)).thenReturn(two.getId());
        handler.handleMessage(outboundOne);
        handler.handleMessage(outboundTwo);
        verify(protocol).handleMessageToClient(one, outboundOne);
        verify(protocol).handleMessageToClient(two, outboundTwo);
        handler.handleTransportError(first, new IllegalStateException());
        assertThat(handler.getStats().getTransportErrorSessions()).isEqualTo(1);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        verify(protocol).afterSessionEnded(argThat(s -> s.getId().equals(one.getId())), eq(CloseStatus.NORMAL), any());
        handler.handleMessage(second, new TextMessage("still-open"));
        verify(protocol).handleMessageFromClient(eq(two), eq(new TextMessage("still-open")), any());
        handler.handleMessage(first, new TextMessage("late"));
        verify(protocol, never()).handleMessageFromClient(any(), eq(new TextMessage("late")), any());
        handler.afterConnectionEstablished(first);
        handler.handleMessage(first, new TextMessage("reconnect"));
        var delivered = org.mockito.ArgumentCaptor.forClass(WebSocketSession.class);
        verify(protocol).handleMessageFromClient(delivered.capture(), eq(new TextMessage("reconnect")), any());
        assertThat(delivered.getValue().getId()).isNotEqualTo(one.getId()).isNotEqualTo(two.getId());
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        assertThat(handler.getStats().getWebSocketSessions()).isZero();
    }

    @Test
    void 開始例外後は登録とbindingを回収し再接続できる() throws Exception {
        MessageChannel inbound = mock(MessageChannel.class);
        SubProtocolHandler protocol = mock(SubProtocolHandler.class);
        when(protocol.getSupportedProtocols()).thenReturn(List.of("test"));
        doThrow(new IllegalStateException("start")).doNothing().when(protocol).afterSessionStarted(any(), any());
        TerminalCloseSubProtocolWebSocketHandler handler = new TerminalCloseSubProtocolWebSocketHandler(inbound, mock(SubscribableChannel.class), () -> null);
        handler.addProtocolHandler(protocol);
        WebSocketSession session = session();
        assertThatThrownBy(() -> handler.afterConnectionEstablished(session)).isInstanceOf(IllegalStateException.class);
        assertThat(handler.getStats().getWebSocketSessions()).isZero();
        handler.handleMessage(session, new TextMessage("late"));
        verify(protocol, never()).handleMessageFromClient(any(), any(), any());
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("new"));
        verify(protocol).handleMessageFromClient(any(), eq(new TextMessage("new")), any());
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(handler.getStats().getWebSocketSessions()).isZero();
    }

    @Test
    void scheduler解決失敗は親登録前に終了し再接続できる() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        TerminalCloseSubProtocolWebSocketHandler handler = new TerminalCloseSubProtocolWebSocketHandler(
                mock(MessageChannel.class), mock(SubscribableChannel.class), () -> {
                    if (attempts.getAndIncrement() == 0) throw new IllegalStateException("scheduler");
                    return null;
                });
        SubProtocolHandler protocol = mock(SubProtocolHandler.class);
        when(protocol.getSupportedProtocols()).thenReturn(List.of("test"));
        handler.addProtocolHandler(protocol);
        WebSocketSession session = session();
        assertThatThrownBy(() -> handler.afterConnectionEstablished(session)).isInstanceOf(IllegalStateException.class);
        assertThat(handler.getStats().getWebSocketSessions()).isZero();
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(handler.getStats().getWebSocketSessions()).isZero();
    }

    @Test
    void 受信中もcloseは完了し以降の受信を拒否する() throws Exception {
        SubProtocolHandler protocol = mock(SubProtocolHandler.class);
        when(protocol.getSupportedProtocols()).thenReturn(List.of("test"));
        TerminalCloseSubProtocolWebSocketHandler handler = new TerminalCloseSubProtocolWebSocketHandler(
                mock(MessageChannel.class), mock(SubscribableChannel.class), () -> null);
        handler.addProtocolHandler(protocol);
        WebSocketSession session = session();
        handler.afterConnectionEstablished(session);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
            return null;
        }).when(protocol).handleMessageFromClient(any(), any(), any());
        Thread receive = new Thread(() -> {
            try { handler.handleMessage(session, new TextMessage("inflight")); }
            catch (Throwable ex) { failure.set(ex); }
        });
        Thread close = new Thread(() -> {
            try { handler.afterConnectionClosed(session, CloseStatus.NORMAL); }
            catch (Throwable ex) { failure.set(ex); }
            finally { closed.countDown(); }
        });
        receive.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            close.start();
            assertThat(closed.await(5, TimeUnit.SECONDS)).as("受信処理の完了前にcloseが完了する").isTrue();
            verify(protocol).afterSessionEnded(any(), eq(CloseStatus.NORMAL), any());
        } finally {
            release.countDown();
            receive.join(5000);
            close.join(5000);
        }
        assertThat(receive.isAlive()).isFalse();
        assertThat(close.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        handler.handleMessage(session, new TextMessage("late"));
        verify(protocol, never()).handleMessageFromClient(any(), eq(new TextMessage("late")), any());
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("same");
        when(session.getPrincipal()).thenReturn(mock(java.security.Principal.class));
        when(session.isOpen()).thenReturn(true);
        when(session.getAcceptedProtocol()).thenReturn("test");
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<String, Object>());
        return session;
    }
}
