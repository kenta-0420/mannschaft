package com.mannschaft.app.config.websocket;

import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

/** 終端拒否後の業務dispatchを入口と実行直前で抑止し、DISCONNECT cleanupは常に通す。 */
public class FatalStompSessionGate implements ExecutorChannelInterceptor {
    public static final String FATAL_SESSION_ATTRIBUTE = "com.mannschaft.websocket.fatalProtocolClose";

    @Override
    @Nullable
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return isFatalBusinessMessage(message) ? null : message;
    }

    @Override
    @Nullable
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return isFatalBusinessMessage(message) ? null : message;
    }

    private static boolean isFatalBusinessMessage(Message<?> message) {
        if (SimpMessageType.DISCONNECT.equals(SimpMessageHeaderAccessor.getMessageType(message.getHeaders()))) {
            return false;
        }
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        return attributes != null && Boolean.TRUE.equals(attributes.get(FATAL_SESSION_ATTRIBUTE));
    }
}
