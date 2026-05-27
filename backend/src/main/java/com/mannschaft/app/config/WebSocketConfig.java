package com.mannschaft.app.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * WebSocket (STOMP) 設定。リアルタイム通知・チャットメッセージ配信に使用する。
 *
 * <p>開発環境ではSimpleBrokerを使用し、本番環境ではValkey（Redis互換）を
 * メッセージブローカーとして使用する想定。</p>
 *
 * <p>{@link WebSocketAuthChannelInterceptor} を inbound チャンネルに登録し、
 * STOMP CONNECT フレームの JWT 検証を行う。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor authChannelInterceptor;

    /**
     * WebSocket ハンドシェイクで許可するオリジン。{@code CorsConfig} と同じプロパティを使用し、
     * 未設定時は開発用デフォルト値（localhost:3000 / localhost:8080）を使用する。
     *
     * <p>設計書: docs/security/01_authorization_baseline.md §5 / docs/security/03_security_headers_and_csp.md §6</p>
     */
    @Value("${mannschaft.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

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
        // 許可オリジンは CorsConfig と同じ環境変数から取得し、ワイルドカード "*" は使用しない（CSRF 対策）。
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
