package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.CreateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatChannelService} の単体テスト。
 * チャンネルCRUD・アーカイブを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelService 単体テスト")
class ChatChannelServiceTest {

    @Mock
    private ChatChannelRepository channelRepository;

    @Mock
    private ChatChannelMemberRepository memberRepository;

    @Mock
    private ChatMapper chatMapper;

    @InjectMocks
    private ChatChannelService chatChannelService;

    private static final Long CHANNEL_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;

    private ChatChannelEntity createChannel() {
        return ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(TEAM_ID)
                .name("テストチャンネル")
                .description("テスト説明")
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // createChannel
    // ========================================
    @Nested
    @DisplayName("createChannel")
    class CreateChannel {

        @Test
        @DisplayName("正常系: チャンネルを作成できる")
        void チャンネルを作成できる() {
            // given
            CreateChannelRequest req = new CreateChannelRequest("TEAM_PUBLIC", TEAM_ID, null,
                    "新チャンネル", "説明", null, false, null);
            ChatChannelEntity saved = createChannel();
            ChannelResponse expected = new ChannelResponse(CHANNEL_ID, "TEAM", TEAM_ID, null,
                    "新チャンネル", null, "説明", false, null, null, null, null, null, false, null, null, null);

            given(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(TEAM_ID, "新チャンネル"))
                    .willReturn(false);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(saved);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.createChannel(req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(channelRepository).save(any(ChatChannelEntity.class));
            verify(memberRepository).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("正常系: メンバー追加も同時に行える")
        void メンバー追加も同時に行える() {
            // given
            Long member1 = 200L;
            Long member2 = 300L;
            CreateChannelRequest req = new CreateChannelRequest("TEAM_PUBLIC", TEAM_ID, null,
                    "新チャンネル", null, null, false, List.of(member1, member2));
            ChatChannelEntity saved = createChannel();

            given(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(TEAM_ID, "新チャンネル"))
                    .willReturn(false);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(saved);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(
                    new ChannelResponse(CHANNEL_ID, "TEAM", TEAM_ID, null, "新チャンネル",
                            null, null, false, null, null, null, null, null, false, null, null, null));

            // when
            chatChannelService.createChannel(req, USER_ID);

            // then
            // OWNER + 2 members = 3 saves
            verify(memberRepository, times(3)).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("異常系: 同名チャンネルが存在する場合はエラー")
        void 同名チャンネルが存在する場合はエラー() {
            // given
            CreateChannelRequest req = new CreateChannelRequest("TEAM_PUBLIC", TEAM_ID, null,
                    "既存チャンネル", null, null, false, null);
            given(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(TEAM_ID, "既存チャンネル"))
                    .willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatChannelService.createChannel(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_NAME_DUPLICATE));
        }
    }

    // ========================================
    // updateChannel
    // ========================================
    @Nested
    @DisplayName("updateChannel")
    class UpdateChannel {

        @Test
        @DisplayName("正常系: チャンネル情報を更新できる")
        void チャンネル情報を更新できる() {
            // given
            ChatChannelEntity channel = createChannel();
            UpdateChannelRequest req = new UpdateChannelRequest("更新名", "更新説明", null);
            ChannelResponse expected = new ChannelResponse(CHANNEL_ID, "TEAM", TEAM_ID, null,
                    "更新名", null, "更新説明", false, null, null, null, null, null, false, null, null, null);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.updateChannel(CHANNEL_ID, req);

            // then
            assertThat(result.getName()).isEqualTo("更新名");
        }

        @Test
        @DisplayName("異常系: アーカイブ済みチャンネルは更新不可")
        void アーカイブ済みチャンネルは更新不可() {
            // given
            ChatChannelEntity channel = createChannel();
            channel.archive();
            UpdateChannelRequest req = new UpdateChannelRequest("更新名", null, null);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            // when & then
            assertThatThrownBy(() -> chatChannelService.updateChannel(CHANNEL_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ARCHIVED));
        }
    }

    // ========================================
    // deleteChannel
    // ========================================
    @Nested
    @DisplayName("deleteChannel")
    class DeleteChannel {

        @Test
        @DisplayName("正常系: チャンネルを論理削除できる")
        void チャンネルを論理削除できる() {
            // given
            ChatChannelEntity channel = createChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);

            // when
            chatChannelService.deleteChannel(CHANNEL_ID);

            // then
            verify(channelRepository).save(any(ChatChannelEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しないチャンネルの削除はエラー")
        void 存在しないチャンネルの削除はエラー() {
            // given
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatChannelService.deleteChannel(CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_NOT_FOUND));
        }
    }

    // ========================================
    // archiveChannel
    // ========================================
    @Nested
    @DisplayName("archiveChannel")
    class ArchiveChannel {

        @Test
        @DisplayName("正常系: チャンネルをアーカイブできる")
        void チャンネルをアーカイブできる() {
            // given
            ChatChannelEntity channel = createChannel();
            ChannelResponse expected = new ChannelResponse(CHANNEL_ID, "TEAM", TEAM_ID, null,
                    "テストチャンネル", null, "テスト説明", false, null, null, null, null, null, true, null, null, null);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.archiveChannel(CHANNEL_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }
    }

    // ========================================
    // listMyChannels
    // ========================================
    @Nested
    @DisplayName("listMyChannels")
    class ListMyChannels {

        @Test
        @DisplayName("正常系: ユーザーの参加チャンネル一覧を取得できる")
        void ユーザーの参加チャンネル一覧を取得できる() {
            // given
            List<ChatChannelEntity> channels = List.of(createChannel());
            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(channels);
            given(chatMapper.toChannelResponseList(channels)).willReturn(List.of());

            // when
            List<ChannelResponse> result = chatChannelService.listMyChannels(USER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }
}
