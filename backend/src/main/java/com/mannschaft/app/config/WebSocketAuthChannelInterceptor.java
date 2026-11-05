package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.common.websocket.StompRejectedException;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;

/**
 * WebSocket STOMP 接続時に認証ソースを確定するチャンネルインターセプター。
 *
 * <p>STOMP {@code CONNECT}（および同義の {@code STOMP} コマンド）で、次の優先順位により接続者を確定し、
 * {@link StompPrincipal}（{@code accessor.setUser(...)}・{@code SimpUserRegistry} 登録 → {@code /user} 宛解決）と
 * セッション属性 {@code userId}（購読認可インターセプタが参照）を<b>同一ソースから同時に</b>設定する
 * （設計書 docs/architecture/websocket_external_broker_valkey.md §2.3 / §2.3.1）。</p>
 * <ol>
 *   <li><b>値のある {@code Authorization: Bearer}</b>: 有効なら採用（ハンドシェイクの Cookie 認証と別ユーザーでも
 *       Bearer 優先。HTTP の {@code JwtAuthenticationFilter} と同じ優先順位）。<b>無効なら Cookie へ切り替えず
 *       {@link StompErrorCode#UNAUTHENTICATED} で拒否</b>する。</li>
 *   <li><b>Bearer なし／空の {@code Bearer }</b>: ハンドシェイク HTTP で {@code JwtAuthenticationFilter} が
 *       HttpOnly Cookie から確立した認証済み {@link Authentication}（{@code accessor.getUser()}）を採用する。
 *       匿名 Authentication は採用しない。</li>
 *   <li>どちらも無い: 匿名接続（Principal・userId とも設定しない。購読ごとに各インターセプタが認可する）。</li>
 * </ol>
 *
 * <p><b>同一ソース原則（§2.3 是正設計 5）</b>: {@code /user} 宛には SUBSCRIBE 認可インターセプタが存在せず、
 * Principal の正当性がユーザー宛配信の唯一の防壁であるため、Principal 名と session userId の一致を不変条件とする。
 * ハンドシェイクで確立した Principal は CONNECT 後に外せない（Spring の仕様）ため、同一ソースで設定できない
 * 状態（無効 Bearer、userId として解釈できない認証済み Principal）は匿名へ落とさず拒否する。</p>
 *
 * <p><b>実装上の注意（§2.3 Y-1）</b>: {@code StompHeaderAccessor.wrap(message)} はコピーを作るため、
 * そこに {@code setUser} しても元メッセージへ伝播しない。
 * {@link MessageHeaderAccessor#getAccessor(Message, Class)} で<b>実アクセサ（可変）</b>を取得して
 * {@code setUser} し、可変アクセサが取得できない場合のみ {@link MessageBuilder} で
 * メッセージを組み直して返す。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_SCHEME = "Bearer";
    private static final String SESSION_USER_ID = "userId";

    /** 無効な資格情報で CONNECT を拒否するときの文言（ERROR フレームの message ヘッダ）。 */
    static final String UNAUTHENTICATED_MESSAGE = "WebSocket 接続の認証情報が無効です";

    private final AuthTokenService authTokenService;
    private final WebSocketNodeIdProvider nodeIdProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !isConnect(accessor.getCommand())) {
            return message;
        }

        Long userId = resolveUserId(accessor);
        if (userId == null) {
            log.debug("WebSocket CONNECT: 資格情報なし（匿名接続を許可）");
            return message;
        }

        // Y-1: 実アクセサが不変の場合は wrap でコピーを作り、組み直したメッセージを返す（§2.3）
        boolean rebuilt = false;
        if (!accessor.isMutable()) {
            accessor = StompHeaderAccessor.wrap(message);
            rebuilt = true;
        }

        // 同一ソースから Principal とセッション属性 userId を同時設定（同一ソース原則・§2.3-5）
        accessor.setUser(new StompPrincipal(String.valueOf(userId)));
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(SESSION_USER_ID, userId);
        }
        log.debug("WebSocket認証成功: userId={}, nodeId={}", userId, nodeIdProvider.getNodeId());

        return rebuilt
                ? MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders())
                : message;
    }

    private static boolean isConnect(StompCommand command) {
        return StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command);
    }

    /**
     * 優先順位に従って接続者の userId を確定する。匿名なら {@code null}。
     *
     * @throws StompRejectedException 提示された資格情報が無効、または同一ソースで設定できない Principal の場合
     */
    private Long resolveUserId(StompHeaderAccessor accessor) {
        Principal handshakeUser = accessor.getUser();
        String bearerToken = extractBearerToken(accessor.getFirstNativeHeader("Authorization"));

        if (bearerToken != null) {
            Long bearerUserId = parseBearer(bearerToken);
            Long handshakeUserId = adoptableUserId(handshakeUser);
            if (handshakeUserId != null && !handshakeUserId.equals(bearerUserId)) {
                // Bearer 優先（HTTP と同じ）。不一致は可観測にしておく（攻撃ではなく別タブのアカウント切替等でも起こる）。
                log.info("WebSocket CONNECT: Bearer とハンドシェイク Cookie の利用者が不一致のため Bearer を採用: "
                        + "bearerUserId={}, cookieUserId={}", bearerUserId, handshakeUserId);
            }
            return bearerUserId;
        }

        if (handshakeUser == null || isAnonymous(handshakeUser)) {
            return null;
        }
        Long handshakeUserId = adoptableUserId(handshakeUser);
        if (handshakeUserId == null) {
            log.warn("WebSocket CONNECT: ハンドシェイクの Principal から userId を確定できないため拒否: type={}",
                    handshakeUser.getClass().getSimpleName());
            throw new StompRejectedException(StompErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return handshakeUserId;
    }

    /**
     * Authorization ヘッダ値から Bearer トークンを取り出す。Bearer スキームでない・値が空（空白のみを含む）の場合は
     * {@code null}（＝Bearer なし扱い）。リロード直後の FE が送る空の {@code "Bearer "} はここで Bearer なしになる。
     */
    private static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_SCHEME)) {
            return null;
        }
        String rest = authorization.substring(BEARER_SCHEME.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            // "BearerXYZ" のような別スキームは Bearer として扱わない
            return null;
        }
        String token = rest.trim();
        return token.isEmpty() ? null : token;
    }

    private Long parseBearer(String token) {
        try {
            Claims claims = authTokenService.parseAccessToken(token);
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            // 失効・改ざん・期限切れ等。Cookie へ黙って切り替えず拒否する（§2.3.1）。
            log.debug("WebSocket CONNECT: Bearer の検証に失敗したため拒否: {}", e.getMessage());
            throw new StompRejectedException(StompErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
    }

    private static boolean isAnonymous(Principal principal) {
        return principal instanceof AnonymousAuthenticationToken;
    }

    /**
     * ハンドシェイクで確立した Principal を採用できる場合にその userId を返す。
     * 採用条件: 認証済みの {@link Authentication}（匿名を除く）で、名前が userId（数値）として解釈できること。
     * {@code JwtAuthenticationFilter} は JWT の subject（userId 文字列）を principal 名に設定する。
     */
    private static Long adoptableUserId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || isAnonymous(principal)
                || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
