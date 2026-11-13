package com.mannschaft.app.config.websocket;

import java.util.function.Supplier;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/** session生成時に既存schedulerを解決し、終端close制御だけを差し替える。 */
public class TerminalCloseSubProtocolWebSocketHandler extends SubProtocolWebSocketHandler {
    private final Supplier<TaskScheduler> scheduler;

    public TerminalCloseSubProtocolWebSocketHandler(MessageChannel inbound, SubscribableChannel outbound,
                                                   Supplier<TaskScheduler> scheduler) {
        super(inbound, outbound);
        this.scheduler = scheduler;
    }

    @Override
    protected WebSocketSession decorateSession(WebSocketSession session) {
        return new DeferredProtocolCloseWebSocketSession(
                session, getSendTimeLimit(), getSendBufferSizeLimit(), scheduler.get());
    }
}
