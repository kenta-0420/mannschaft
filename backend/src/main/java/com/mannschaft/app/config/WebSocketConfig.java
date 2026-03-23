package com.mannschaft.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket (STOMP) 設定。リアルタイム通知・チャットメッセージ配信に使用する。
 *
 * <p>開発環境ではSimpleBrokerを使用し、本番環境ではValkey（Redis互換）を
 * メッセージブローカーとして使用する想定。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // クライアントが購読するプレフィックス（/topic: ブロードキャスト、/queue: ユーザー個別）
        config.enableSimpleBroker("/topic", "/queue");
        // クライアントが送信するプレフィックス
        config.setApplicationDestinationPrefixes("/app");
        // ユーザー個別通知のプレフィックス（/user/{userId}/queue/notifications）
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocketエンドポイント。SockJS fallback対応。
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
