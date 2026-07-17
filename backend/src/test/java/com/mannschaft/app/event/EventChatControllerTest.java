package com.mannschaft.app.event;

import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.EventChatChannelService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.controller.EventChatController;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link EventChatController} の単体テスト。
 * イベント専用チャットチャンネル取得APIを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventChatController 単体テスト")
class EventChatControllerTest {

    @Mock
    private EventChatChannelService eventChatChannelService;

    @Mock
    private ChatMapper chatMapper;

    @Mock
    private EventScopeAccessGuard eventScopeAccessGuard;

    @InjectMocks
    private EventChatController eventChatController;

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID = 42L;

    /**
     * {@code SecurityUtils.getCurrentUserId()} は {@code SecurityContextHolder} を直接参照するため、
     * Controller を直接メソッド呼び出しする本テストでは認証コンテキストを手動で張る必要がある
     * （{@code EventScopeAccessGuard} 敷設に伴い新規追加）。
     */
    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("GET /{eventId}/channel")
    class GetEventChannel {

        @Test
        @DisplayName("チャンネルが存在する場合は 200 と ChannelResponse を返す")
        void チャンネル存在時は200を返す() {
            // given
            ChatChannelEntity channelEntity = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .teamId(10L)
                    .name("テストイベント チャット")
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            ChannelResponse channelResponse = ChannelResponse.builder()
                    .id(1L)
                    .identity(new ChannelResponse.ChannelIdentityDto("EVENT_CHAT", 10L, null))
                    .meta(new ChannelResponse.ChannelMetaDto("テストイベント チャット", null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, 1L))
                    .source(new ChannelResponse.ChannelSourceDto("EVENT", EVENT_ID))
                    .build();
            given(eventChatChannelService.findByEventId(EVENT_ID)).willReturn(Optional.of(channelEntity));
            given(chatMapper.toChannelResponse(channelEntity)).willReturn(channelResponse);

            // when
            ResponseEntity<ApiResponse<ChannelResponse>> response = eventChatController.getEventChannel(EVENT_ID);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isEqualTo(channelResponse);
        }

        @Test
        @DisplayName("チャンネルが存在しない場合は 404 を返す")
        void チャンネル未存在時は404を返す() {
            // given
            given(eventChatChannelService.findByEventId(EVENT_ID)).willReturn(Optional.empty());

            // when
            ResponseEntity<ApiResponse<ChannelResponse>> response = eventChatController.getEventChannel(EVENT_ID);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
