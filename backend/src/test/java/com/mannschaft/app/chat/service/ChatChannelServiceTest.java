package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.CreateChannelRequest;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
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
 * チャンネルCRUD・アーカイブ・会話開始・Zimmer招待を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelService 単体テスト")
class ChatChannelServiceTest {

    @Mock
    private ChatChannelRepository channelRepository;

    @Mock
    private ChatChannelMemberRepository memberRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private ChatMapper chatMapper;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ChatContactFolderItemRepository chatContactFolderItemRepository;

    @InjectMocks
    private ChatChannelService chatChannelService;

    private static final Long CHANNEL_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long PARTNER_ID = 200L;
    private static final Long INVITEE_ID = 300L;
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

    private ChatChannelEntity createDmChannel() {
        return ChatChannelEntity.builder()
                .channelType(ChannelType.DM)
                .createdBy(USER_ID)
                .build();
    }

    private UserEntity createUser(DmReceiveFrom dmReceiveFrom) {
        return UserEntity.builder()
                .email("test@example.com")
                .dmReceiveFrom(dmReceiveFrom)
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

    // ========================================
    // startConversation
    // ========================================
    @Nested
    @DisplayName("startConversation")
    class StartConversation {

        private ChannelResponse stubResponse() {
            return new ChannelResponse(CHANNEL_ID, "DM", null, null,
                    null, null, null, false, null, null, null, null, null, false, null, null, null);
        }

        @Test
        @DisplayName("正常系: 1名指定 → 既存Kabineがあれば200(created=false)で返却")
        void 既存Kabineがある場合は返却() {
            // given
            ChatChannelEntity existing = createDmChannel();
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            given(channelRepository.findExistingDm(USER_ID, PARTNER_ID)).willReturn(Optional.of(existing));
            given(chatMapper.toChannelResponse(existing)).willReturn(stubResponse());

            // when
            ChatChannelService.ConversationResult result =
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID));

            // then
            assertThat(result.created()).isFalse();
        }

        @Test
        @DisplayName("正常系: 1名指定 → 既存Kabineがなければ新規作成(created=true)")
        void 既存Kabineがない場合は新規作成() {
            // given
            ChatChannelEntity saved = createDmChannel();
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            given(channelRepository.findExistingDm(USER_ID, PARTNER_ID)).willReturn(Optional.empty());
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(saved);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toChannelResponse(saved)).willReturn(stubResponse());

            // when
            ChatChannelService.ConversationResult result =
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID));

            // then
            assertThat(result.created()).isTrue();
        }

        @Test
        @DisplayName("正常系: 2名以上指定 → 新規Zimmer作成(created=true)")
        void 複数名指定で新規Zimmer作成() {
            // given
            ChatChannelEntity saved = ChatChannelEntity.builder()
                    .channelType(ChannelType.GROUP_DM).createdBy(USER_ID).build();
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(false);
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(false);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(saved);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toChannelResponse(saved)).willReturn(
                    new ChannelResponse(2L, "GROUP_DM", null, null,
                            null, null, null, false, null, null, null, null, null, false, null, null, null));

            // when
            ChatChannelService.ConversationResult result =
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID, INVITEE_ID));

            // then
            assertThat(result.created()).isTrue();
            // OWNER + 2 MEMBER = 3 saves
            verify(memberRepository, times(3)).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("異常系: 自分自身を指定するとエラー")
        void 自分自身を指定するとエラー() {
            assertThatThrownBy(() ->
                    chatChannelService.startConversation(USER_ID, List.of(USER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_SELF_DM));
        }

        @Test
        @DisplayName("異常系: 相手がブロックしている場合はエラー")
        void 相手がブロックしている場合はエラー() {
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(true);

            assertThatThrownBy(() ->
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        }

        @Test
        @DisplayName("異常系: DM受信制限（TEAM_MEMBERS_ONLY）で共通チームなし")
        void DM受信制限で拒否() {
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.TEAM_MEMBERS_ONLY)));
            // receiver.getId() はビルダーでセットできないためanyを使用
            given(userRoleRepository.existsSharedTeam(eq(USER_ID), any())).willReturn(false);

            assertThatThrownBy(() ->
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.DM_RECEIVE_RESTRICTED));
        }
    }

    // ========================================
    // inviteToZimmer
    // ========================================
    @Nested
    @DisplayName("inviteToZimmer")
    class InviteToZimmer {

        private InviteToZimmerRequest buildRequest(boolean shareHistory) {
            // リフレクションを使わずにフィールドを設定するためビルダー的ヘルパー
            // （DTOはLombokの@Getter/@NoArgsConstructorのため、テスト用に匿名サブクラスで代替）
            return new InviteToZimmerRequest() {
                {
                    // フィールドへの直接アクセスはできないためJSON経由が本来だが、
                    // ここではReflectionTestUtilsで設定する
                }

                @Override
                public List<Long> getUserIds() { return List.of(INVITEE_ID); }

                @Override
                public boolean isShareHistory() { return shareHistory; }
            };
        }

        private ChannelResponse stubZimmerResponse() {
            return new ChannelResponse(99L, "GROUP_DM", null, null,
                    null, null, null, false, null, null, null, null, null, false, null, null, null);
        }

        @Test
        @DisplayName("正常系: Kabineから新Zimmerを作成できる（履歴なし）")
        void Kabineから新Zimmerを作成できる() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            ChatChannelEntity zimmer = ChatChannelEntity.builder()
                    .channelType(ChannelType.GROUP_DM).createdBy(USER_ID).build();
            ChatChannelMemberEntity memberA = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(USER_ID).build();
            ChatChannelMemberEntity memberB = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(PARTNER_ID).build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(zimmer);
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA, memberB));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(false);
            given(memberRepository.existsByChannelIdAndUserId(zimmer.getId(), INVITEE_ID)).willReturn(false);
            given(chatMapper.toChannelResponse(zimmer)).willReturn(stubZimmerResponse());

            // when
            ChannelResponse result = chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false));

            // then
            assertThat(result).isNotNull();
            assertThat(result.getChannelType()).isEqualTo("GROUP_DM");
            verify(channelRepository).save(any(ChatChannelEntity.class)); // Zimmer作成
        }

        @Test
        @DisplayName("正常系: 履歴共有あり → Kabineメッセージがコピーされる")
        void 履歴共有ありでメッセージコピー() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            ChatChannelEntity zimmer = ChatChannelEntity.builder()
                    .channelType(ChannelType.GROUP_DM).createdBy(USER_ID).build();
            ChatMessageEntity msg = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(USER_ID).body("こんにちは").build();
            ChatChannelMemberEntity memberA = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(USER_ID).build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(zimmer);
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(false);
            given(memberRepository.existsByChannelIdAndUserId(zimmer.getId(), INVITEE_ID)).willReturn(false);
            given(messageRepository.findByChannelIdOrderByCreatedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(msg));
            given(messageRepository.save(any(ChatMessageEntity.class)))
                    .willReturn(ChatMessageEntity.builder().channelId(99L).build());
            given(chatMapper.toChannelResponse(zimmer)).willReturn(stubZimmerResponse());

            // when
            chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(true));

            // then
            verify(messageRepository).findByChannelIdOrderByCreatedAtAsc(CHANNEL_ID);
            verify(messageRepository).save(any(ChatMessageEntity.class));
        }

        @Test
        @DisplayName("異常系: DM以外のチャンネルは拒否")
        void DM以外のチャンネルは拒否() {
            // given
            ChatChannelEntity teamChannel = createChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(teamChannel));

            // when & then
            assertThatThrownBy(() ->
                    chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_NOT_DM));
        }

        @Test
        @DisplayName("異常系: Kabineのメンバー以外は操作不可")
        void Kabineのメンバー以外は操作不可() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            // when & then
            assertThatThrownBy(() ->
                    chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        }

        @Test
        @DisplayName("異常系: 招待対象が呼び出しユーザーをブロックしている場合は拒否")
        void 招待対象がブロックしている場合は拒否() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            ChatChannelMemberEntity memberA = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(USER_ID).build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(
                    ChatChannelEntity.builder().channelType(ChannelType.GROUP_DM).build());
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() ->
                    chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        }
    }
}
