package com.mannschaft.app.village.listener;

import com.mannschaft.app.village.service.VillageLobbyPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageWebSocketSessionListener} 単体テスト（F17.1 Phase 2）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>userId が存在する場合、active-lobbies を走査して leave を呼ぶ</li>
 *   <li>active-lobbies が空の場合は何もしない</li>
 *   <li>userId が未設定の場合は何もしない</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageWebSocketSessionListener 単体テスト")
class VillageWebSocketSessionListenerTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final Long USER_ID = 42L;

    @Mock
    VillageLobbyPresenceService presenceService;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    SetOperations<String, String> setOps;

    @InjectMocks
    VillageWebSocketSessionListener listener;

    @BeforeEach
    void setUp() {
        // userId 未設定テストでは opsForSet() が呼ばれないため lenient で登録する
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("onDisconnect_userId存在する場合_active_lobbiesを走査してleaveを呼ぶ")
    void onDisconnect_withUserId_callsLeaveForEachVillage() {
        // given
        given(setOps.members(contains("active-lobbies")))
                .willReturn(Set.of(VILLAGE_ID.toString()));

        SessionDisconnectEvent event = createDisconnectEvent(USER_ID);

        // when
        listener.onDisconnect(event);

        // then
        verify(presenceService).leave(eq(VILLAGE_ID), eq(USER_ID));
        verify(redisTemplate).delete(contains("active-lobbies"));
    }

    @Test
    @DisplayName("onDisconnect_active_lobbiesが空の場合_何もしない")
    void onDisconnect_emptyActiveLobbies_doesNothing() {
        // given
        given(setOps.members(anyString())).willReturn(Collections.emptySet());

        SessionDisconnectEvent event = createDisconnectEvent(USER_ID);

        // when
        listener.onDisconnect(event);

        // then
        verify(presenceService, never()).leave(any(), any());
    }

    @Test
    @DisplayName("onDisconnect_userId未設定の場合_何もしない")
    void onDisconnect_noUserId_doesNothing() {
        // given（userId なし）
        SessionDisconnectEvent event = createDisconnectEvent(null);

        // when
        listener.onDisconnect(event);

        // then
        verify(presenceService, never()).leave(any(), any());
        verify(setOps, never()).members(anyString());
    }

    // ========== ヘルパ ==========

    private SessionDisconnectEvent createDisconnectEvent(Long userId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT);
        if (userId != null) {
            accessor.setSessionAttributes(Map.of("userId", userId));
        } else {
            accessor.setSessionAttributes(new HashMap<>());
        }
        accessor.setSessionId("test-session-id");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, "test-session-id", CloseStatus.NORMAL);
    }
}
