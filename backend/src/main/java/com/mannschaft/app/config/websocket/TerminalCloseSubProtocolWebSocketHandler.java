package com.mannschaft.app.config.websocket;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/** transport単位の内部IDと終端close制御を適用する。 */
public class TerminalCloseSubProtocolWebSocketHandler extends SubProtocolWebSocketHandler {
    private final Supplier<TaskScheduler> scheduler;
    private final Map<WebSocketSession, InternalSession> sessions =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public TerminalCloseSubProtocolWebSocketHandler(MessageChannel inbound, SubscribableChannel outbound,
                                                   Supplier<TaskScheduler> scheduler) {
        super(inbound, outbound);
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!session.isOpen()) return;
        InternalSession internal = new InternalSession(session, scheduler.get());
        synchronized (internal) {
            synchronized (sessions) {
                if (sessions.containsKey(session)) throw new IllegalStateException("session already established");
                sessions.put(session, internal);
            }
            try {
                super.afterConnectionEstablished(internal);
                // 親がisOpenの再確認で登録せず戻った場合もbindingを残さない。
                if (!session.isOpen() && sessions.remove(session, internal)) {
                    super.afterConnectionClosed(internal, CloseStatus.NORMAL);
                }
            } catch (Exception | Error failure) {
                if (sessions.remove(session, internal)) {
                    try {
                        super.afterConnectionClosed(internal, CloseStatus.SERVER_ERROR);
                    } catch (Exception | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        InternalSession internal = sessions.get(session);
        if (internal == null) return;
        synchronized (internal) {
            if (sessions.get(session) != internal) return;
        }
        // 受理済みの処理がDB待ちでも、切断callbackを妨げない。
        super.handleMessage(internal, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        InternalSession internal = sessions.get(session);
        if (internal == null) return;
        synchronized (internal) {
            if (sessions.get(session) != internal) return;
        }
        super.handleTransportError(internal, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        InternalSession internal = sessions.get(session);
        if (internal == null) return;
        synchronized (internal) {
            if (sessions.remove(session, internal)) super.afterConnectionClosed(internal, status);
        }
    }

    @Override
    protected WebSocketSession decorateSession(WebSocketSession session) {
        return ((InternalSession) session).decorated;
    }

    private final class InternalSession extends WebSocketSessionDecorator {
        private final String id = UUID.randomUUID().toString();
        private final WebSocketSession decorated;

        InternalSession(WebSocketSession delegate, TaskScheduler scheduler) {
            super(delegate);
            this.decorated = new DeferredProtocolCloseWebSocketSession(
                    this, getSendTimeLimit(), getSendBufferSizeLimit(), scheduler);
        }

        @Override
        public String getId() {
            return id;
        }
    }
}
