package com.mannschaft.app.config.websocket;

import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * CMP-261110-2011 正式試練用の最小API骨格。
 * 現在は既存frameworkへ委譲するのみ。drain、期限予約、fatal markerは未実装。
 * 試練をコンパイル可能にするための型であり、修復済み製品ではない。
 */
public class DeferredProtocolCloseWebSocketSession extends ConcurrentWebSocketSessionDecorator {
    public DeferredProtocolCloseWebSocketSession(WebSocketSession delegate, int sendTimeLimit,
                                                int bufferSizeLimit, @Nullable TaskScheduler scheduler) {
        super(delegate, sendTimeLimit, bufferSizeLimit);
    }
}
