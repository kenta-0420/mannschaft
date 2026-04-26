package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.dto.ChatChannelEventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatChannelEventPublisher} の単体テスト。
 * F04.2.1 §3.10.1: チャンネル状態変化イベントの STOMP 配信を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelEventPublisher 単体テスト")
class ChatChannelEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChatChannelEventPublisher publisher;

    private static final Long CHANNEL_ID = 42L;
    private static final Long KICKED_USER_ID = 100L;
    private static final String EXPECTED_DESTINATION = "/topic/channels/42/events";

    @Test
    @DisplayName("publishMemberKicked: MEMBER_KICKED ペイロードが正しいトピックに送信される")
    void publishMemberKickedで正しいトピックとペイロードが送信される() {
        // when
        publisher.publishMemberKicked(CHANNEL_ID, KICKED_USER_ID);

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ChatChannelEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(ChatChannelEventPayload.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo(EXPECTED_DESTINATION);
        assertThat(payloadCaptor.getValue().type()).isEqualTo("MEMBER_KICKED");
        assertThat(payloadCaptor.getValue().userId()).isEqualTo(KICKED_USER_ID);
    }

    @Test
    @DisplayName("publishChannelDeleted: CHANNEL_DELETED ペイロードが正しいトピックに送信される")
    void publishChannelDeletedで正しいトピックとペイロードが送信される() {
        // when
        publisher.publishChannelDeleted(CHANNEL_ID);

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ChatChannelEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(ChatChannelEventPayload.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo(EXPECTED_DESTINATION);
        assertThat(payloadCaptor.getValue().type()).isEqualTo("CHANNEL_DELETED");
        assertThat(payloadCaptor.getValue().userId()).isNull();
    }

    @Test
    @DisplayName("publishChannelArchived: CHANNEL_ARCHIVED ペイロードが正しいトピックに送信される")
    void publishChannelArchivedで正しいトピックとペイロードが送信される() {
        // when
        publisher.publishChannelArchived(CHANNEL_ID);

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ChatChannelEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(ChatChannelEventPayload.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo(EXPECTED_DESTINATION);
        assertThat(payloadCaptor.getValue().type()).isEqualTo("CHANNEL_ARCHIVED");
        assertThat(payloadCaptor.getValue().userId()).isNull();
    }

    @Test
    @DisplayName("publishChannelUnarchived: CHANNEL_UNARCHIVED ペイロードが正しいトピックに送信される")
    void publishChannelUnarchivedで正しいトピックとペイロードが送信される() {
        // when
        publisher.publishChannelUnarchived(CHANNEL_ID);

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ChatChannelEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(ChatChannelEventPayload.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo(EXPECTED_DESTINATION);
        assertThat(payloadCaptor.getValue().type()).isEqualTo("CHANNEL_UNARCHIVED");
        assertThat(payloadCaptor.getValue().userId()).isNull();
    }
}
