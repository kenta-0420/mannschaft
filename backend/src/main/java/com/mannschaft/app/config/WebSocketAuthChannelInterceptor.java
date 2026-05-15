package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket STOMP 接続時に JWT を検証するチャンネルインターセプター。
 *
 * <p>STOMP CONNECT フレームの Authorization ヘッダーから Bearer トークンを取り出し、
 * {@link AuthTokenService} で検証してユーザーIDをセッション属性に保存する。</p>
 *
 * <p>トークンが存在しない・無効な場合は接続を拒否せず、セッション属性には保存しない。
 * 後続のタイピングコントローラー等でセッション属性の userId が null であれば
 * 未認証として扱う。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService authTokenService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String token = authHeader.substring(BEARER_PREFIX.length());
                try {
                    Claims claims = authTokenService.parseAccessToken(token);
                    Long userId = Long.valueOf(claims.getSubject());

                    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                    if (sessionAttributes != null) {
                        sessionAttributes.put("userId", userId);
                        log.debug("WebSocket認証成功: userId={}", userId);
                    }
                } catch (Exception e) {
                    log.debug("WebSocket JWT検証失敗（接続は許可）: {}", e.getMessage());
                }
            } else {
                log.debug("WebSocket CONNECT: Authorization ヘッダーなし（匿名接続を許可）");
            }
        }

        return message;
    }
}
