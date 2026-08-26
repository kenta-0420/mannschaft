package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.websocket.StompPrincipal;
import com.mannschaft.app.websocket.WebSocketNodeIdProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket STOMP 接続時に JWT を検証するチャンネルインターセプター。
 *
 * <p>STOMP CONNECT フレームの Authorization ヘッダーから Bearer トークンを取り出し、
 * {@link AuthTokenService} で検証してユーザーIDをセッション属性に保存する。
 * あわせて同一の JWT 検証結果から {@link StompPrincipal} を {@code accessor.setUser(...)} で確立し、
 * {@code SimpUserRegistry} にユーザー→セッションを登録させる（設計書 §2.3 — これにより
 * {@code convertAndSendToUser} の {@code /user/{userId}/queue/...} 宛先解決が成立する）。</p>
 *
 * <p><b>同一ソース原則（§2.3 是正設計 5・AC-5）</b>: セッション属性の userId と Principal 名
 * （{@code getName()} = userId 文字列）は同一の JWT 検証結果から同時に設定する。
 * {@code /user} 宛には SUBSCRIBE 認可インターセプタが存在せず、Principal の正当性が
 * ユーザー宛配信の唯一の防壁であるため、両値の一致を不変条件とする。</p>
 *
 * <p><b>実装上の注意（§2.3 Y-1）</b>: {@code StompHeaderAccessor.wrap(message)} はコピーを作るため、
 * そこに {@code setUser} しても元メッセージへ伝播しない。
 * {@link MessageHeaderAccessor#getAccessor(Message, Class)} で<b>実アクセサ（可変）</b>を取得して
 * {@code setUser} し、可変アクセサが取得できない場合のみ {@link MessageBuilder} で
 * メッセージを組み直して返す。</p>
 *
 * <p>トークンが存在しない・無効な場合は接続を拒否せず、セッション属性にも Principal にも設定しない
 * （匿名接続許容・§2.3 是正設計 6。Principal 不在セッションは {@code SimpUserRegistry} に登録されず
 * {@code /user} 宛解決 0 件で無害）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService authTokenService;
    private final WebSocketNodeIdProvider nodeIdProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug("WebSocket CONNECT: Authorization ヘッダーなし（匿名接続を許可）");
            return message;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        Long userId;
        try {
            Claims claims = authTokenService.parseAccessToken(token);
            userId = Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            log.debug("WebSocket JWT検証失敗（接続は許可）: {}", e.getMessage());
            return message;
        }

        // Y-1: 実アクセサが不変の場合は wrap でコピーを作り、組み直したメッセージを返す（§2.3）
        boolean rebuilt = false;
        if (!accessor.isMutable()) {
            accessor = StompHeaderAccessor.wrap(message);
            rebuilt = true;
        }

        // 同一の JWT 検証結果から Principal とセッション属性 userId を同時設定（同一ソース原則・§2.3-5）
        accessor.setUser(new StompPrincipal(String.valueOf(userId)));
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put("userId", userId);
        }
        log.debug("WebSocket認証成功: userId={}, nodeId={}", userId, nodeIdProvider.getNodeId());

        return rebuilt
                ? MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders())
                : message;
    }
}
