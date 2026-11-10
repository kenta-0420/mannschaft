package com.mannschaft.app.config.websocket;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/** CMP-261110-2011 配線試練用API骨格。decorateSessionは既存base動作のまま。 */
public class TerminalCloseSubProtocolWebSocketHandler extends SubProtocolWebSocketHandler {
    public TerminalCloseSubProtocolWebSocketHandler(MessageChannel inbound, SubscribableChannel outbound,
                                                   TaskScheduler scheduler) {
        super(inbound, outbound);
    }
}
