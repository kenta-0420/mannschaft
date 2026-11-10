package com.mannschaft.app.config.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-261110-2011 AC-22〜27の正式試練。実frameworkのdecode/channel/dispatchを使う。
 * 制御する外部境界はWebSocket transportのみ。実認可/業務Beanの代替には使わない。
 * 未実行でありred未認定。DISCONNECTの実broker cleanupは別の実接続ITが担う。
 */
class FatalStompSessionGateTest {
    private static final int SEND_LIMIT = 10_000;
    private static final int BUFFER_LIMIT = 64 * 1024;
    private final FatalStompSessionGate gate = new FatalStompSessionGate();
    private final ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
    private final MessageHandler noOp = message -> { };

    @Test
    void protocolClose入口でserver属性を物理closeより先に確定する() throws Exception {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        WebSocketSession delegate = transport("fatal-marker", attributes);
        AtomicBoolean markerAtPhysicalClose = new AtomicBoolean();
        doAnswer(call -> {
            markerAtPhysicalClose.set(Boolean.TRUE.equals(attributes.get(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE)));
            return null;
        }).when(delegate).close(any(CloseStatus.class));
        var session = new DeferredProtocolCloseWebSocketSession(delegate, SEND_LIMIT, BUFFER_LIMIT, mock(TaskScheduler.class));
        session.close(CloseStatus.PROTOCOL_ERROR);
        assertThat(markerAtPhysicalClose).as("物理close開始前にserver markerがtrue").isTrue();
        assertThat(attributes.get(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void nativeHeaderだけではfatalを偽造できない() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        accessor.setNativeHeader(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, "true");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(gate.preSend(message, channel)).isSameAs(message);
        assertThat(gate.beforeHandle(message, channel, noOp)).isSameAs(message);
    }

    @Test
    void nativeFalseでserverFatalを解除できない() {
        Map<String, Object> attributes = fatalAttributes();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(attributes);
        accessor.setNativeHeader(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, "false");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(gate.preSend(message, channel)).isNull();
        assertThat(gate.beforeHandle(message, channel, noOp)).isNull();
        assertThat(attributes.get(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void fatalは他sessionへ伝播しない() {
        Message<?> fatal = message(SimpMessageType.MESSAGE, "session-A", fatalAttributes());
        Message<?> normal = message(SimpMessageType.MESSAGE, "session-B", new ConcurrentHashMap<>());
        assertThat(gate.preSend(fatal, channel)).isNull();
        assertThat(gate.preSend(normal, channel)).isSameAs(normal);
        assertThat(gate.beforeHandle(normal, channel, noOp)).isSameAs(normal);
    }

    @Test
    void attrsNullの通常sessionは従来どおり通す() {
        Message<?> message = message(SimpMessageType.MESSAGE, "attrs-null", null);
        assertThat(gate.preSend(message, channel)).isSameAs(message);
        assertThat(gate.beforeHandle(message, channel, noOp)).isSameAs(message);
    }

    @Test
    void fatalのDISCONNECT以外は両入口で同じく落とす() {
        for (SimpMessageType type : SimpMessageType.values()) {
            if (type == SimpMessageType.DISCONNECT) continue;
            Message<?> message = message(type, "fatal-types", fatalAttributes());
            assertThat(gate.preSend(message, channel)).as("preSend %s", type).isNull();
            assertThat(gate.beforeHandle(message, channel, noOp)).as("beforeHandle %s", type).isNull();
        }
    }

    @Test
    void samePayload先頭拒否後のSENDをpreSendで業務dispatchさせない() throws Exception {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        WebSocketSession delegate = transport("same-payload", attributes);
        var session = new DeferredProtocolCloseWebSocketSession(delegate, SEND_LIMIT, BUFFER_LIMIT, mock(TaskScheduler.class));
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        inbound.addInterceptor(gate);
        inbound.addInterceptor(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel ignored) {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.SUBSCRIBE) {
                    throw new MessageDeliveryException(message, "fixture subscription rejection");
                }
                return message;
            }
        });
        List<SimpMessageType> dispatched = new ArrayList<>();
        inbound.subscribe(message -> dispatched.add(SimpMessageHeaderAccessor.getMessageType(message.getHeaders())));
        StompSubProtocolHandler protocol = new StompSubProtocolHandler();
        protocol.afterSessionStarted(session, inbound);
        try {
            protocol.handleMessageFromClient(session,
                    new TextMessage("CONNECT\naccept-version:1.2\n\n\u0000"), inbound);
            protocol.handleMessageFromClient(session,
                    new TextMessage("SUBSCRIBE\nid:reject\ndestination:/topic/reject\n\n\u0000"
                            + "SEND\ndestination:/app/probe\n\n\u0000"), inbound);
            assertThat(dispatched).contains(SimpMessageType.CONNECT).doesNotContain(SimpMessageType.MESSAGE);
        } finally {
            protocol.afterSessionEnded(session, CloseStatus.NORMAL, inbound);
        }
    }

    @Test
    void preSend通過済みqueued後続はbeforeHandleで落とす() {
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(queued::add);
        inbound.addInterceptor(gate);
        AtomicInteger handled = new AtomicInteger();
        inbound.subscribe(message -> handled.incrementAndGet());
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        assertThat(inbound.send(message(SimpMessageType.MESSAGE, "queued", attributes))).isTrue();
        assertThat(queued).hasSize(1);
        attributes.put(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, true);
        Runnable task = queued.poll();
        assertThat(task).isNotNull();
        task.run();
        assertThat(handled).as("fatal前にenqueueした仕事もhandlerへ渡さない").hasValue(0);
    }

    @Test
    void beforeHandle通過済みinflightは巻き戻さず後続だけ落とす() {
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        inbound.addInterceptor(gate);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        AtomicInteger handled = new AtomicInteger();
        inbound.subscribe(message -> {
            attributes.put(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, true);
            handled.incrementAndGet();
        });
        inbound.send(message(SimpMessageType.MESSAGE, "inflight", attributes));
        assertThat(handled).hasValue(1);
        inbound.send(message(SimpMessageType.MESSAGE, "inflight", attributes));
        assertThat(handled).as("既に開始した1件は保持し、後続は抑止").hasValue(1);
    }

    @Test
    void fatalでもsyntheticDISCONNECTを両入口で通す() {
        Message<?> disconnect = message(SimpMessageType.DISCONNECT, "synthetic", fatalAttributes());
        assertThat(gate.preSend(disconnect, channel)).isSameAs(disconnect);
        assertThat(gate.beforeHandle(disconnect, channel, noOp)).isSameAs(disconnect);
    }

    @Test
    void normalCloseはfatalMarkerを設定しない() throws Exception {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        var session = new DeferredProtocolCloseWebSocketSession(transport("normal", attributes),
                SEND_LIMIT, BUFFER_LIMIT, mock(TaskScheduler.class));
        session.close(CloseStatus.NORMAL);
        assertThat(attributes).doesNotContainKey(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE);
    }

    private static Map<String, Object> fatalAttributes() {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, true);
        return attributes;
    }

    private static Message<byte[]> message(SimpMessageType type, String id, Map<String, Object> attributes) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(id);
        accessor.setSessionAttributes(attributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static WebSocketSession transport(String id, Map<String, Object> attributes) {
        WebSocketSession delegate = mock(WebSocketSession.class);
        when(delegate.getId()).thenReturn(id);
        when(delegate.isOpen()).thenReturn(true);
        when(delegate.getAttributes()).thenReturn(attributes);
        return delegate;
    }
}
