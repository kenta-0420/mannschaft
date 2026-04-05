package com.mannschaft.app.signage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * デジタルサイネージ WebSocket 設定。
 * STOMP over SockJS エンドポイントとシンプルブローカーを構成する。
 * SimpMessagingTemplate は Spring が自動で Bean 登録するため @Bean 宣言不要。
 */
@Configuration
@EnableWebSocketMessageBroker
public class SignageWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP エンドポイントを登録する。
     * SockJS フォールバックを有効化し、全オリジンからの接続を許可する。
     *
     * @param registry エンドポイントレジストリ
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/signage")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * メッセージブローカーを構成する。
     * /topic プレフィックスへのシンプルブローカーと /app アプリケーション宛先プレフィックスを設定する。
     *
     * @param registry ブローカーレジストリ
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic/** をシンプルブローカーが処理する（クライアントへの配信先）
        registry.enableSimpleBroker("/topic");
        // /app/** をアプリケーション側（@MessageMapping）が処理する
        registry.setApplicationDestinationPrefixes("/app");
    }
}
