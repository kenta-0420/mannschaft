package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.common.websocket.StompRejectedException;
import com.mannschaft.app.websocket.StompPrincipal;
import com.mannschaft.app.websocket.WebSocketNodeIdProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-261104-0337 AC 0337-3 / 0337-9: STOMP CONNECT 時の認証ソース選択（B 案）の単体テスト。
 *
 * <p>優先順位: ①CONNECT の Bearer（値あり・有効）→ ②ハンドシェイクで確立した認証済み Authentication
 * （HttpOnly Cookie 由来。匿名 Authentication は不採用）→ ③匿名。値ありの無効 Bearer は
 * 別ソースへ黙って切り替えず UNAUTHENTICATED で拒否する。いずれの経路でも Principal 名と
 * session 属性 userId を同一ソースから同時に設定する（設計書 §2.3 同一ソース原則）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebSocketAuthChannelInterceptor CONNECT 認証ソース選択 UT（CMP-261104-0337）")
class WebSocketAuthChannelInterceptorTest {

    private static final long BEARER_USER_ID = 501L;
    private static final long COOKIE_USER_ID = 502L;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private WebSocketNodeIdProvider nodeIdProvider;

    @Mock
    private MessageChannel channel;

    @InjectMocks
    private WebSocketAuthChannelInterceptor interceptor;

    // ─── ヘルパー ───────────────────────────────────────────────

    private Message<byte[]> connectMessage(StompCommand command, String authorization, Principal handshakeUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setSessionAttributes(new HashMap<>());
        if (handshakeUser != null) {
            accessor.setUser(handshakeUser);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static UsernamePasswordAuthenticationToken cookieAuthentication(long userId) {
        return new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void stubValidBearer(String token, long userId) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(authTokenService.parseAccessToken(token)).thenReturn(claims);
    }

    private static StompHeaderAccessor accessorOf(Message<?> message) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        return accessor != null ? accessor : StompHeaderAccessor.wrap(message);
    }

    private static Object sessionUserId(Message<?> message) {
        Map<String, Object> attrs = accessorOf(message).getSessionAttributes();
        return attrs == null ? null : attrs.get("userId");
    }

    private static void assertSameSource(Message<?> result, long expectedUserId) {
        Principal user = accessorOf(result).getUser();
        assertThat(user).as("Principal は StompPrincipal で確立される").isInstanceOf(StompPrincipal.class);
        assertThat(user.getName()).as("Principal 名").isEqualTo(String.valueOf(expectedUserId));
        assertThat(sessionUserId(result)).as("session userId は Principal と同一ソース").isEqualTo(expectedUserId);
    }

    // ─── ① Bearer（従来経路・非回帰） ───────────────────────────

    @Test
    @DisplayName("有効な Bearer のみ → Principal と session userId が Bearer のユーザー（非回帰）")
    void 有効なBearerのみ() {
        stubValidBearer("valid", BEARER_USER_ID);

        Message<?> result = interceptor.preSend(connectMessage(StompCommand.CONNECT, "Bearer valid", null), channel);

        assertSameSource(result, BEARER_USER_ID);
    }

    @Test
    @DisplayName("0337-9: Bearer と Cookie が別ユーザー → Bearer 優先で Principal・userId とも Bearer のユーザー")
    void BearerとCookieが別ユーザーならBearer優先() {
        stubValidBearer("valid", BEARER_USER_ID);

        Message<?> result = interceptor.preSend(
                connectMessage(StompCommand.CONNECT, "Bearer valid", cookieAuthentication(COOKIE_USER_ID)), channel);

        assertSameSource(result, BEARER_USER_ID);
    }

    // ─── ② ハンドシェイク（Cookie）認証の採用 ─────────────────────

    @Test
    @DisplayName("0337-3: Authorization なし＋Cookie 認証ハンドシェイク → Principal と session userId が Cookie のユーザー")
    void Bearer無しでCookie認証を採用() {
        Message<?> result = interceptor.preSend(
                connectMessage(StompCommand.CONNECT, null, cookieAuthentication(COOKIE_USER_ID)), channel);

        assertSameSource(result, COOKIE_USER_ID);
        verify(authTokenService, never()).parseAccessToken(anyString());
    }

    @Test
    @DisplayName("0337-3: 空の 'Bearer ' ＋Cookie 認証 → Bearer 無しと同じく Cookie のユーザーを採用")
    void 空BearerでCookie認証を採用() {
        Message<?> result = interceptor.preSend(
                connectMessage(StompCommand.CONNECT, "Bearer ", cookieAuthentication(COOKIE_USER_ID)), channel);

        assertSameSource(result, COOKIE_USER_ID);
        verify(authTokenService, never()).parseAccessToken(anyString());
    }

    @Test
    @DisplayName("0337-3: 空白だけの 'Bearer   ' ＋Cookie 認証 → Cookie のユーザーを採用")
    void 空白BearerでCookie認証を採用() {
        Message<?> result = interceptor.preSend(
                connectMessage(StompCommand.CONNECT, "Bearer    ", cookieAuthentication(COOKIE_USER_ID)), channel);

        assertSameSource(result, COOKIE_USER_ID);
    }

    @Test
    @DisplayName("0337-3: STOMP コマンド（CONNECT 同義）でも Cookie 認証を採用する")
    void STOMPコマンドでもCookie認証を採用() {
        Message<?> result = interceptor.preSend(
                connectMessage(StompCommand.STOMP, null, cookieAuthentication(COOKIE_USER_ID)), channel);

        assertSameSource(result, COOKIE_USER_ID);
    }

    @Test
    @DisplayName("STOMP コマンド（CONNECT 同義）の無効 Bearer も UNAUTHENTICATED で拒否する")
    void STOMPコマンドの無効Bearerも拒否() {
        when(authTokenService.parseAccessToken("broken")).thenThrow(new IllegalStateException("invalid"));

        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(StompCommand.STOMP, "Bearer broken", null), channel))
                .isInstanceOfSatisfying(StompRejectedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StompErrorCode.UNAUTHENTICATED));
    }

    // ─── ③ 匿名 ──────────────────────────────────────────────

    @Test
    @DisplayName("0337-3: Bearer も Cookie も無い → 匿名（Principal・session userId とも未設定、接続は許可）")
    void 両方無ければ匿名() {
        Message<?> result = interceptor.preSend(connectMessage(StompCommand.CONNECT, null, null), channel);

        assertThat(accessorOf(result).getUser()).isNull();
        assertThat(sessionUserId(result)).isNull();
    }

    @Test
    @DisplayName("0337-9: 匿名 Authentication は採用しない（session userId 未設定・StompPrincipal を確立しない）")
    void 匿名Authenticationは採用しない() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        Message<?> result = interceptor.preSend(connectMessage(StompCommand.CONNECT, null, anonymous), channel);

        assertThat(sessionUserId(result)).isNull();
        assertThat(accessorOf(result).getUser()).isNotInstanceOf(StompPrincipal.class);
    }

    @Test
    @DisplayName("未認証状態の Authentication（isAuthenticated=false）は採用せず UNAUTHENTICATED で拒否する")
    void 未認証Authenticationは拒否() {
        UsernamePasswordAuthenticationToken unauthenticated =
                UsernamePasswordAuthenticationToken.unauthenticated(String.valueOf(COOKIE_USER_ID), null);

        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(StompCommand.CONNECT, null, unauthenticated), channel))
                .isInstanceOfSatisfying(StompRejectedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StompErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("名前が userId として解釈できない認証済み Principal は採用せず UNAUTHENTICATED で拒否する")
    void 数値でないPrincipal名は拒否() {
        UsernamePasswordAuthenticationToken odd = new UsernamePasswordAuthenticationToken(
                "not-a-number", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThatThrownBy(() -> interceptor.preSend(connectMessage(StompCommand.CONNECT, null, odd), channel))
                .isInstanceOfSatisfying(StompRejectedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StompErrorCode.UNAUTHENTICATED));
    }

    // ─── 無効 Bearer ─────────────────────────────────────────

    @Test
    @DisplayName("0337-9: 無効 Bearer＋有効 Cookie → Cookie へ黙って切り替えず UNAUTHENTICATED で拒否")
    void 無効Bearerと有効Cookieは拒否() {
        when(authTokenService.parseAccessToken("expired")).thenThrow(new IllegalStateException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(StompCommand.CONNECT, "Bearer expired", cookieAuthentication(COOKIE_USER_ID)), channel))
                .isInstanceOfSatisfying(StompRejectedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StompErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("0337-9: 無効 Bearer のみ → UNAUTHENTICATED で拒否（資格情報を提示したのに無効）")
    void 無効Bearerのみは拒否() {
        when(authTokenService.parseAccessToken("expired")).thenThrow(new IllegalStateException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(StompCommand.CONNECT, "Bearer expired", null), channel))
                .isInstanceOfSatisfying(StompRejectedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StompErrorCode.UNAUTHENTICATED));
    }

    // ─── CONNECT 以外は素通し ───────────────────────────────────

    @Test
    @DisplayName("SUBSCRIBE は素通し（認証ソース選択は CONNECT 系のみ）")
    void SUBSCRIBEは素通し() {
        Message<byte[]> subscribe = connectMessage(StompCommand.SUBSCRIBE, "Bearer whatever", null);

        Message<?> result = interceptor.preSend(subscribe, channel);

        assertThat(result).isSameAs(subscribe);
        verify(authTokenService, never()).parseAccessToken(anyString());
    }
}
