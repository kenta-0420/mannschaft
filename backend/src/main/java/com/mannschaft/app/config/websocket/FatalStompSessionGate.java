package com.mannschaft.app.config.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

/**
 * CMP-261110-2011 正式試練用API骨格。現在は従来どおり全メッセージを通す。
 * fatal server属性の判定とDISCONNECT以外の抑止は未実装。
 */
public class FatalStompSessionGate implements ExecutorChannelInterceptor {
    public static final String FATAL_SESSION_ATTRIBUTE = "com.mannschaft.websocket.fatalProtocolClose";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return message;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return message;
    }
}
