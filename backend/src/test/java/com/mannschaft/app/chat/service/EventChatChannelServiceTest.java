package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.event.EventScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EventChatChannelService} の単体テスト。
 * イベント専用チャットチャンネルの作成・重複防止・アーカイブを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventChatChannelService 単体テスト")
class EventChatChannelServiceTest {

    @Mock
    private ChatChannelRepository chatChannelRepository;

    @InjectMocks
    private EventChatChannelService eventChatChannelService;

    private static final Long EVENT_ID = 42L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final String EVENT_TITLE = "サマーキャンプ2026";

    // ========================================
    // createForEvent
    // ========================================
    @Nested
    @DisplayName("createForEvent")
    class CreateForEvent {

        @Test
        @DisplayName("正常系(TEAM): sourceType=EVENT・channelType=EVENT_CHAT のチャンネルが保存される")
        void TEAMスコープでチャンネルを作成できる() {
            // given
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.empty());
            ChatChannelEntity savedChannel = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .teamId(TEAM_ID)
                    .name(EVENT_TITLE + " チャット")
                    .isPrivate(false)
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            given(chatChannelRepository.save(any(ChatChannelEntity.class))).willReturn(savedChannel);

            // when
            ChatChannelEntity result = eventChatChannelService.createForEvent(
                    EVENT_ID, EventScopeType.TEAM, TEAM_ID, EVENT_TITLE);

            // then
            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(chatChannelRepository).save(captor.capture());
            ChatChannelEntity captured = captor.getValue();
            assertThat(captured.getChannelType()).isEqualTo(ChannelType.EVENT_CHAT);
            assertThat(captured.getSourceType()).isEqualTo("EVENT");
            assertThat(captured.getSourceId()).isEqualTo(EVENT_ID);
            assertThat(captured.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(captured.getOrganizationId()).isNull();
            assertThat(captured.getName()).isEqualTo(EVENT_TITLE + " チャット");
            assertThat(captured.getIsPrivate()).isFalse();
            assertThat(result).isEqualTo(savedChannel);
        }

        @Test
        @DisplayName("正常系(ORGANIZATION): organizationId がセットされ teamId が null になる")
        void ORGANIZATIONスコープでチャンネルを作成できる() {
            // given
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.empty());
            ChatChannelEntity savedChannel = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .organizationId(ORG_ID)
                    .name(EVENT_TITLE + " チャット")
                    .isPrivate(false)
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            given(chatChannelRepository.save(any(ChatChannelEntity.class))).willReturn(savedChannel);

            // when
            ChatChannelEntity result = eventChatChannelService.createForEvent(
                    EVENT_ID, EventScopeType.ORGANIZATION, ORG_ID, EVENT_TITLE);

            // then
            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(chatChannelRepository).save(captor.capture());
            ChatChannelEntity captured = captor.getValue();
            assertThat(captured.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(captured.getTeamId()).isNull();
            assertThat(result).isEqualTo(savedChannel);
        }

        @Test
        @DisplayName("重複防止: チャンネルが既に存在する場合は save を呼ばずに既存チャンネルを返す")
        void チャンネルが既に存在する場合は重複作成しない() {
            // given
            ChatChannelEntity existing = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .teamId(TEAM_ID)
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.of(existing));

            // when
            ChatChannelEntity result = eventChatChannelService.createForEvent(
                    EVENT_ID, EventScopeType.TEAM, TEAM_ID, EVENT_TITLE);

            // then
            verify(chatChannelRepository, never()).save(any());
            assertThat(result).isEqualTo(existing);
        }
    }

    // ========================================
    // archiveForEvent
    // ========================================
    @Nested
    @DisplayName("archiveForEvent")
    class ArchiveForEvent {

        @Test
        @DisplayName("正常系: チャンネルが存在する場合に isArchived=true に更新される")
        void チャンネルをアーカイブできる() {
            // given
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .teamId(TEAM_ID)
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.of(channel));
            given(chatChannelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);

            // when
            eventChatChannelService.archiveForEvent(EVENT_ID);

            // then
            assertThat(channel.getIsArchived()).isTrue();
            verify(chatChannelRepository).save(channel);
        }

        @Test
        @DisplayName("チャンネルが存在しない場合はノーオペレーション（例外なし）")
        void チャンネルが存在しない場合はスキップ() {
            // given
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.empty());

            // when - 例外が発生しないことを確認
            eventChatChannelService.archiveForEvent(EVENT_ID);

            // then
            verify(chatChannelRepository, never()).save(any());
        }
    }

    // ========================================
    // findByEventId
    // ========================================
    @Nested
    @DisplayName("findByEventId")
    class FindByEventId {

        @Test
        @DisplayName("チャンネルが存在する場合は Optional にくるまれて返る")
        void チャンネルが存在する場合() {
            // given
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .sourceType("EVENT")
                    .sourceId(EVENT_ID)
                    .build();
            given(chatChannelRepository.findBySourceTypeAndSourceId("EVENT", EVENT_ID))
                    .willReturn(Optional.of(channel));

            // when
            Optional<ChatChannelEntity> result = eventChatChannelService.findByEventId(EVENT_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(channel);
        }

        @Test
        @DisplayName("チャンネルが存在しない場合は empty が返る")
        void チャンネルが存在しない場合() {
            // given
            given(chatChannelRepository.findBySourceTypeAndSourceId(eq("EVENT"), eq(EVENT_ID)))
                    .willReturn(Optional.empty());

            // when
            Optional<ChatChannelEntity> result = eventChatChannelService.findByEventId(EVENT_ID);

            // then
            assertThat(result).isEmpty();
        }
    }
}
