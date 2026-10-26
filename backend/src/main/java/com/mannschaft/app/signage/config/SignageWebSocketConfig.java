package com.mannschaft.app.signage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * デジタルサイネージ WebSocket 設定（STOMP エンドポイント {@code /ws/signage} の登録のみ）。
 *
 * <p><b>ブローカー設定は宣言しない（設計書 websocket_external_broker_valkey.md §2.1）</b>:
 * 同一コンテキストの全 {@code WebSocketMessageBrokerConfigurer} は単一の
 * {@code MessageBrokerRegistry} にマージされるため、ここで {@code enableSimpleBroker("/topic")} を
 * 呼ぶと {@code WebSocketConfig} の {@code ("/topic", "/queue")} 宣言と<b>順序依存で上書き合戦</b>になり、
 * サイネージ側が後勝ちした場合 {@code /queue} がブローカーから脱落して
 * {@code convertAndSendToUser} のユーザー宛配信が全滅する（red テストで実証済み・
 * {@code WebSocketPrincipalWiringIntegrationTest} 参照）。ブローカー設定
 * （{@code /topic}・{@code /queue}・{@code /app}・{@code /user}）は {@code WebSocketConfig} の
 * 単一 Registry 宣言に<b>一本化</b>されており、本クラスはエンドポイント登録のみを担う。</p>
 *
 * <p>許可オリジンも {@code WebSocketConfig} と同じ {@code mannschaft.allowed-origins}
 * （環境変数由来）に統一する（ワイルドカード {@code "*"} は CSRF 対策方針と不整合のため廃止・§2.1）。</p>
 *
 * <p>SimpMessagingTemplate は Spring が自動で Bean 登録するため @Bean 宣言不要。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class SignageWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * WebSocket ハンドシェイクで許可するオリジン。{@code WebSocketConfig} / {@code CorsConfig} と
     * 同じプロパティを使用し、未設定時は開発用デフォルト値を使用する。
     */
    @Value("${mannschaft.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * STOMP エンドポイントを登録する。
     * SockJS フォールバックを有効化し、許可オリジンは環境変数由来の制限に従う。
     *
     * @param registry エンドポイントレジストリ
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addEndpoint("/ws/signage")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }
}
