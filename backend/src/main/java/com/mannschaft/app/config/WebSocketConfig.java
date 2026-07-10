package com.mannschaft.app.config;

import com.mannschaft.app.chat.ws.ChatChannelSubscriptionInterceptor;
import com.mannschaft.app.match.live.MatchLiveSubscriptionInterceptor;
import com.mannschaft.app.reservation.ws.EmergencyClosureSubscriptionInterceptor;
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
 *
 * <p>さらに {@link MatchLiveSubscriptionInterceptor} を<b>認証インターセプタの後段</b>に登録し、
 * F08.10 ライブ観戦トピック（{@code /topic/matches/{matchId}/live}）の SUBSCRIBE 認可を行う
 * （CONNECT で確定した session userId を参照するため順序が重要・07 §J.3）。</p>
 *
 * <p>同様に {@link EmergencyClosureSubscriptionInterceptor} を認証インターセプタの後段に登録し、
 * F03.4+ 臨時休業確認状況トピック（{@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations}）の
 * SUBSCRIBE 認可（当該チーム ADMIN 限定）を行う。</p>
 *
 * <p>さらに {@link ChatChannelSubscriptionInterceptor} を認証インターセプタの後段に登録し、
 * チャットチャネルトピック（{@code /topic/channels/{channelId}}）の SUBSCRIBE 認可
 * （チャネルメンバーシップ検査・非メンバー拒否）を行い、既存 IDOR を閉塞する（§2.6・AC-11）。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor authChannelInterceptor;
    private final MatchLiveSubscriptionInterceptor matchLiveSubscriptionInterceptor;
    private final EmergencyClosureSubscriptionInterceptor emergencyClosureSubscriptionInterceptor;
    private final ChatChannelSubscriptionInterceptor chatChannelSubscriptionInterceptor;

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
        // 認証（CONNECT で session userId を確定）→ 購読認可（SUBSCRIBE で各トピックの認可検証）の順で登録する。
        // 購読認可は CONNECT 時に確定した session userId を参照するため、必ず認証の後段に置く（07 §J.3）。
        registration.interceptors(
                authChannelInterceptor,
                matchLiveSubscriptionInterceptor,
                emergencyClosureSubscriptionInterceptor,
                chatChannelSubscriptionInterceptor);
    }
}
