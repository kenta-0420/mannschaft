package com.mannschaft.app.event;

import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.EventChatChannelService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.controller.EventChatController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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

    @InjectMocks
    private EventChatController eventChatController;

    private static final Long EVENT_ID = 1L;

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
            ChannelResponse channelResponse = new ChannelResponse(
                    1L, "EVENT_CHAT", 10L, null, "テストイベント チャット",
                    null, null, false, null, null, null,
                    "EVENT", EVENT_ID, false, false, 1L, null, null
            );
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
