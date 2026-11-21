package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.websocket.StompRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC1/4/8: server bindingとDB境界の配送失敗を注入する純試練。
 * ReaderだけをDB故障の注入口として代替する。実DB・実TCP・独立TXの証明はITで行う。
 */
class SignageSocketGuardTest {
    private static final String DESTINATION = "/topic/signage/10/emergency";
    private final SignageSocketAccessReader reader = mock(SignageSocketAccessReader.class);
    private final SignageSocketSessions sessions = new SignageSocketSessions();
    private final SignageDeliveryInterceptor outbound = new SignageDeliveryInterceptor(sessions, reader);
    private final SignageSubscriptionInterceptor inbound = new SignageSubscriptionInterceptor(sessions, reader);

    @Test
    void AC8_DB失敗は当該recipientだけ拒否し正常recipientへ継続する() {
        establish("broken", 1L);
        establish("healthy", 2L);
        sessions.bind("broken", "sub", DESTINATION);
        sessions.bind("healthy", "sub", DESTINATION);
        when(reader.permits(1L, 10L, "127.0.0.1")).thenThrow(new DataAccessResourceFailureException("synthetic"));
        when(reader.permits(2L, 10L, "127.0.0.1")).thenReturn(true);
        assertThat(outbound.preSend(delivery("broken", "sub", DESTINATION), null)).isNull();
        Message<?> healthy = delivery("healthy", "sub", DESTINATION);
        assertThat(outbound.preSend(healthy, null)).isSameAs(healthy);
        verify(reader).permits(1L, 10L, "127.0.0.1");
        verify(reader).permits(2L, 10L, "127.0.0.1");
    }

    @Test
    void AC4_publisherが資格属性を偽装しても未登録recipientとwildcardへ配送しない() {
        establish("wildcard", 1L);
        assertThat(outbound.preSend(delivery("unknown", "sub", DESTINATION), null)).isNull();
        assertThat(outbound.preSend(delivery("wildcard", "sub", DESTINATION), null)).isNull();
        sessions.bind("wildcard", "sub", "/topic/signage/10/update");
        assertThat(outbound.preSend(delivery("wildcard", "sub", DESTINATION), null)).isNull();
        verifyNoInteractions(reader);
    }

    @Test
    void AC4_unsubscribeと切断でbindingと資格を回収する() {
        establish("session", 1L);
        sessions.bind("session", "sub", DESTINATION);
        assertThat(sessions.binding("session", "sub", DESTINATION)).isPresent();
        sessions.unsubscribe("session", "sub");
        assertThat(sessions.binding("session", "sub", DESTINATION)).isEmpty();
        sessions.bind("session", "sub", DESTINATION);
        sessions.close("session", new HashMap<>());
        assertThat(sessions.binding("session", "sub", DESTINATION)).isEmpty();
        assertThat(sessions.session("session")).isEmpty();
    }

    @Test
    void AC8_非Signageと制御frameはDB照合しない() {
        Message<?> ordinary = delivery("unknown", "sub", "/topic/other");
        assertThat(outbound.preSend(ordinary, null)).isSameAs(ordinary);
        var heartbeat = MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.HEARTBEAT).build();
        assertThat(outbound.preSend(heartbeat, null)).isSameAs(heartbeat);
        verifyNoInteractions(reader);
    }

    @Test
    void AC1_CONNECTheaderは検証直後に除去し直接peerだけを資格へ束縛する() {
        String raw = "device-secret";
        var accessor = connect(raw);
        accessor.setNativeHeader("X-Forwarded-For", "203.0.113.1");
        when(reader.authenticate(raw, "127.0.0.1"))
                .thenReturn(Optional.of(new SignageSocketAccess(1L, 10L, (LocalDateTime) null, null)));
        var result = inbound.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
        assertThat(StompHeaderAccessor.wrap(result).getNativeHeader("X-Signage-Token")).isNull();
        assertThat(sessions.session("session")).isPresent();
        assertThat(sessions.session("session").orElseThrow().toString()).doesNotContain(raw);
        verify(reader).authenticate(raw, "127.0.0.1");
    }

    @Test
    void AC1_DB例外の本文にrawtokenがあってもCONNECT拒否へ転載しない() {
        String raw = "device-secret";
        when(reader.authenticate(raw, "127.0.0.1"))
                .thenThrow(new DataAccessResourceFailureException(raw));
        var accessor = connect(raw);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> inbound.preSend(message, null)).hasMessageNotContaining(raw);
        assertThat(sessions.session("session")).isEmpty();
    }

    @Test
    void AC4_認可完了前に切断eventを処理しても資格を復活させない() throws Exception {
        var accessor = connect("device-secret");
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        var authenticating = new CountDownLatch(1);
        var completeAuthentication = new CountDownLatch(1);
        when(reader.authenticate("device-secret", "127.0.0.1")).thenAnswer(invocation -> {
            authenticating.countDown();
            assertThat(completeAuthentication.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new SignageSocketAccess(1L, 10L, (LocalDateTime) null, null));
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var connection = executor.submit(() -> catchThrowable(() -> inbound.preSend(message, null)));
            try {
                assertThat(authenticating.await(5, TimeUnit.SECONDS)).isTrue();
                // 認可のDB待ち中にも別threadでcleanupが完了する。monitorをDB照合中に保持させない。
                executor.submit(() -> sessions.disconnected(disconnectEvent(accessor)))
                        .get(5, TimeUnit.SECONDS);
                assertThat(sessions.session("session")).isEmpty();
            } finally {
                completeAuthentication.countDown();
            }
            Throwable rejected = connection.get(5, TimeUnit.SECONDS);
            assertThat(sessions.session("session")).as("切断後の認可完了で資格を復活させない").isEmpty();
            assertThat(rejected).isInstanceOf(StompRejectedException.class);
        }
    }

    @Test
    void AC4_切断済みsessionへCONNECTを処理しても資格を再登録しない() {
        var accessor = connect("device-secret");
        sessions.disconnected(disconnectEvent(accessor));
        when(reader.authenticate("device-secret", "127.0.0.1"))
                .thenReturn(Optional.of(new SignageSocketAccess(1L, 10L, (LocalDateTime) null, null)));

        Throwable rejected = catchThrowable(() -> inbound.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));

        assertThat(sessions.session("session")).isEmpty();
        assertThat(rejected).isInstanceOf(StompRejectedException.class);
    }

    @Test
    void AC4_通常CONNECT後のDISCONNECTと重複eventで資格とbindingを回収する() {
        var accessor = connect("device-secret");
        when(reader.authenticate("device-secret", "127.0.0.1"))
                .thenReturn(Optional.of(new SignageSocketAccess(1L, 10L, (LocalDateTime) null, null)));
        inbound.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
        sessions.bind("session", "sub", DESTINATION);
        assertThat(sessions.binding("session", "sub", DESTINATION)).isPresent();
        var disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("session");
        disconnect.setSessionAttributes(accessor.getSessionAttributes());

        inbound.preSend(MessageBuilder.createMessage(new byte[0], disconnect.getMessageHeaders()), null);
        sessions.disconnected(disconnectEvent(accessor));
        sessions.disconnected(disconnectEvent(accessor));

        assertThat(sessions.session("session")).isEmpty();
        assertThat(sessions.binding("session", "sub", DESTINATION)).isEmpty();
    }

    private SessionDisconnectEvent disconnectEvent(StompHeaderAccessor connection) {
        var accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("session");
        accessor.setSessionAttributes(connection.getSessionAttributes());
        return new SessionDisconnectEvent(this,
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), "session", CloseStatus.NORMAL);
    }

    private StompHeaderAccessor connect(String raw) {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session");
        accessor.setSessionAttributes(new HashMap<>(Map.of(SignagePeerHandshakeInterceptor.PEER_IP_ATTRIBUTE, "127.0.0.1")));
        accessor.setNativeHeader("X-Signage-Token", raw);
        return accessor;
    }

    private void establish(String sessionId, Long tokenId) {
        sessions.establish(sessionId, new HashMap<>(), new SignageSocketAccess(tokenId, 10L, (LocalDateTime) null, null), "127.0.0.1");
    }

    private Message<byte[]> delivery(String recipient, String subscriptionId, String destination) {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE)
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, recipient)
                .setHeader(SimpMessageHeaderAccessor.SUBSCRIPTION_ID_HEADER, subscriptionId)
                .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination)
                .setHeader(SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, Map.of("tokenId", 1L, "screenId", 10L))
                .setHeader("nativeHeaders", Map.of("X-Signage-Token", java.util.List.of("forged"))).build();
    }
}
