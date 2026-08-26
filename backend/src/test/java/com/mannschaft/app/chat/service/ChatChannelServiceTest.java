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
import com.mannschaft.app.chat.dto.UpdateInquiryChannelRequest;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository.ChannelMemberCount;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private ChatChannelEventPublisher eventPublisher;

    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private AccessControlService accessControlService;

    /**
     * チャットドメインの認可判定は本ガードに集約されている。
     * 可否そのもの（メンバーシップ・管理権限・DM 受信範囲）は {@code ChatChannelAccessGuardTest} が検証し、
     * 本テストは「取得済みの実体・正しい引数でガードへ委譲しているか」を検証する。
     */
    @Mock
    private ChatChannelAccessGuard channelAccessGuard;

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
            ChannelResponse expected = ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("TEAM", TEAM_ID, null))
                    .meta(new ChannelResponse.ChannelMetaDto("新チャンネル", null, "説明"))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();

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
                    ChannelResponse.builder()
                            .id(CHANNEL_ID)
                            .identity(new ChannelResponse.ChannelIdentityDto("TEAM", TEAM_ID, null))
                            .meta(new ChannelResponse.ChannelMetaDto("新チャンネル", null, null))
                            .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                            .build());

            // when
            chatChannelService.createChannel(req, USER_ID);

            // then
            // OWNER + 2 members = 3 saves
            verify(memberRepository, times(3)).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("認可委譲: 作成前にスコープ所属検証へ委譲し、拒否されればチャンネルを保存しない")
        void スコープ所属検証へ委譲する() {
            // given: 非公開のチーム非公開チャンネル（＝チーム ADMIN 以上を要する種別）
            CreateChannelRequest req = new CreateChannelRequest("TEAM_PRIVATE", TEAM_ID, null,
                    "秘密チャンネル", null, null, true, null);
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelCreationScope(
                            ChannelType.TEAM_PRIVATE, TEAM_ID, null, true, USER_ID);

            // when & then
            assertThatThrownBy(() -> chatChannelService.createChannel(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(channelRepository, never()).save(any(ChatChannelEntity.class));
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
            ChannelResponse expected = ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("TEAM", TEAM_ID, null))
                    .meta(new ChannelResponse.ChannelMetaDto("更新名", null, "更新説明"))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.updateChannel(CHANNEL_ID, req, USER_ID);

            // then
            assertThat(result.getMeta().name()).isEqualTo("更新名");
        }

        @Test
        @DisplayName("認可委譲: リクエスト値ではなく取得済みチャンネル実体で管理権限を検証する")
        void 管理権限検証は取得済み実体で委譲される() {
            // given
            ChatChannelEntity channel = createChannel();
            UpdateChannelRequest req = new UpdateChannelRequest("更新名", null, null);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class)))
                    .willReturn(ChannelResponse.builder().id(CHANNEL_ID).build());

            // when
            chatChannelService.updateChannel(CHANNEL_ID, req, USER_ID);

            // then: リポジトリから引いたエンティティそのものが渡る（スコープ詐称を許さない）
            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(channelAccessGuard).requireChannelAdminAccess(captor.capture(), eq(USER_ID));
            assertThat(captor.getValue()).isSameAs(channel);
            assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("認可委譲: 管理権限が無ければ更新は保存されない")
        void 管理権限が無ければ保存されない() {
            // given
            ChatChannelEntity channel = createChannel();
            UpdateChannelRequest req = new UpdateChannelRequest("更新名", null, null);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelAdminAccess(channel, USER_ID);

            // when & then
            assertThatThrownBy(() -> chatChannelService.updateChannel(CHANNEL_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(channelRepository, never()).save(any(ChatChannelEntity.class));
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
            assertThatThrownBy(() -> chatChannelService.updateChannel(CHANNEL_ID, req, USER_ID))
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
            chatChannelService.deleteChannel(CHANNEL_ID, USER_ID);

            // then
            verify(channelRepository).save(any(ChatChannelEntity.class));
            // F04.2.1 §3.10.1: 削除イベントが発出されることを検証
            verify(eventPublisher).publishChannelDeleted(CHANNEL_ID);
        }

        @Test
        @DisplayName("異常系: 存在しないチャンネルの削除はエラー")
        void 存在しないチャンネルの削除はエラー() {
            // given
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatChannelService.deleteChannel(CHANNEL_ID, USER_ID))
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
            ChannelResponse expected = ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("TEAM", TEAM_ID, null))
                    .meta(new ChannelResponse.ChannelMetaDto("テストチャンネル", null, "テスト説明"))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, true, null))
                    .build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.archiveChannel(CHANNEL_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            // F04.2.1 §3.10.1: アーカイブイベントが発出されることを検証
            verify(eventPublisher).publishChannelArchived(CHANNEL_ID);
        }
    }

    // ========================================
    // unarchiveChannel
    // ========================================
    @Nested
    @DisplayName("unarchiveChannel")
    class UnarchiveChannel {

        @Test
        @DisplayName("正常系: アーカイブ済みチャンネルを解除できる")
        void アーカイブ済みチャンネルを解除できる() {
            // given
            ChatChannelEntity channel = createChannel();
            channel.archive(); // アーカイブ状態にセット
            ChannelResponse expected = ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("TEAM", TEAM_ID, null))
                    .meta(new ChannelResponse.ChannelMetaDto("テストチャンネル", null, "テスト説明"))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(expected);

            // when
            ChannelResponse result = chatChannelService.unarchiveChannel(CHANNEL_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            // F04.2.1 §3.10.1: アーカイブ解除イベントが発出されることを検証
            verify(eventPublisher).publishChannelUnarchived(CHANNEL_ID);
        }

        @Test
        @DisplayName("異常系: アーカイブされていないチャンネルは CHANNEL_NOT_ARCHIVED")
        void アーカイブされていないチャンネルは例外() {
            // given
            ChatChannelEntity channel = createChannel(); // isArchived = false

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            // when / then
            assertThatThrownBy(() -> chatChannelService.unarchiveChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ChatErrorCode.CHANNEL_NOT_ARCHIVED.getMessage());
        }
    }

    // ========================================
    // listMyChannels（per-user 拡張: memberCount / viewer / dmPartner）
    // ========================================
    @Nested
    @DisplayName("listMyChannels — チャンネル契約 per-user 拡張")
    class ListMyChannels {

        private static final Long TEAM_CH_ID = 1L;
        private static final Long DM_CH_ID = 2L;

        /** id を採番した TEAM_PUBLIC チャンネル。 */
        private ChatChannelEntity teamChannelWithId() {
            ChatChannelEntity ch = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(TEAM_ID).name("一般").build();
            ReflectionTestUtils.setField(ch, "id", TEAM_CH_ID);
            return ch;
        }

        /** id を採番した DM チャンネル。 */
        private ChatChannelEntity dmChannelWithId() {
            ChatChannelEntity ch = ChatChannelEntity.builder()
                    .channelType(ChannelType.DM).build();
            ReflectionTestUtils.setField(ch, "id", DM_CH_ID);
            return ch;
        }

        private ChannelResponse baseResponse(Long id, String channelType) {
            return ChannelResponse.builder()
                    .id(id)
                    .identity(new ChannelResponse.ChannelIdentityDto(channelType, null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();
        }

        private ChannelMemberCount count(Long channelId, long n) {
            return new ChannelMemberCount() {
                @Override public Long getChannelId() { return channelId; }
                @Override public long getMemberCount() { return n; }
            };
        }

        @Test
        @DisplayName("空: 参加チャンネルが無ければ空リスト")
        void 参加チャンネルなしで空リスト() {
            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of());

            assertThat(chatChannelService.listMyChannels(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("AC-B1: 各要素に memberCount が入り countGroupedByChannelIds と一致する")
        void AC_B1_memberCountが集計値と一致() {
            ChatChannelEntity team = teamChannelWithId();
            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(team));
            given(memberRepository.findByUserId(USER_ID)).willReturn(List.of(
                    ChatChannelMemberEntity.builder().channelId(TEAM_CH_ID).userId(USER_ID).build()));
            given(memberRepository.countGroupedByChannelIds(anyList()))
                    .willReturn(List.of(count(TEAM_CH_ID, 5L)));
            given(chatMapper.toChannelResponse(team)).willReturn(baseResponse(TEAM_CH_ID, "TEAM_PUBLIC"));

            List<ChannelResponse> result = chatChannelService.listMyChannels(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMemberCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("AC-B2: viewer が呼出ユーザーのメンバー行と一致する")
        void AC_B2_viewerが自分のメンバー行と一致() {
            ChatChannelEntity team = teamChannelWithId();
            ChatChannelMemberEntity myMember = ChatChannelMemberEntity.builder()
                    .channelId(TEAM_CH_ID).userId(USER_ID).role(ChannelMemberRole.ADMIN)
                    .unreadCount(3).isMuted(true).isPinned(false).category("仕事").build();
            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(team));
            given(memberRepository.findByUserId(USER_ID)).willReturn(List.of(myMember));
            given(memberRepository.countGroupedByChannelIds(anyList()))
                    .willReturn(List.of(count(TEAM_CH_ID, 5L)));
            given(chatMapper.toChannelResponse(team)).willReturn(baseResponse(TEAM_CH_ID, "TEAM_PUBLIC"));

            ChannelResponse.ViewerStateDto viewer = chatChannelService.listMyChannels(USER_ID).get(0).getViewer();

            assertThat(viewer).isNotNull();
            assertThat(viewer.unreadCount()).isEqualTo(3);
            assertThat(viewer.isMuted()).isTrue();
            assertThat(viewer.isPinned()).isFalse();
            assertThat(viewer.category()).isEqualTo("仕事");
            assertThat(viewer.role()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("AC-B3: DM の dmPartner が自分以外メンバー＋表示名で構築される")
        void AC_B3_DMのdmPartnerが相手で構築() {
            ChatChannelEntity dm = dmChannelWithId();
            ChatChannelMemberEntity myMember = ChatChannelMemberEntity.builder()
                    .channelId(DM_CH_ID).userId(USER_ID).role(ChannelMemberRole.OWNER).build();
            ChatChannelMemberEntity partnerMember = ChatChannelMemberEntity.builder()
                    .channelId(DM_CH_ID).userId(PARTNER_ID).role(ChannelMemberRole.MEMBER).build();
            UserEntity partner = UserEntity.builder()
                    .email("p@example.com").displayName("田中太郎").avatarUrl("http://x/a.png").build();
            ReflectionTestUtils.setField(partner, "id", PARTNER_ID);

            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(dm));
            given(memberRepository.findByUserId(USER_ID)).willReturn(List.of(myMember));
            given(memberRepository.countGroupedByChannelIds(anyList()))
                    .willReturn(List.of(count(DM_CH_ID, 2L)));
            given(memberRepository.findByChannelIdInAndUserIdNot(anyList(), eq(USER_ID)))
                    .willReturn(List.of(partnerMember));
            given(userRepository.findAllById(anyList())).willReturn(List.of(partner));
            given(chatMapper.toChannelResponse(dm)).willReturn(baseResponse(DM_CH_ID, "DM"));

            ChannelResponse.DmPartnerDto dmPartner =
                    chatChannelService.listMyChannels(USER_ID).get(0).getDmPartner();

            assertThat(dmPartner).isNotNull();
            assertThat(dmPartner.userId()).isEqualTo(PARTNER_ID);
            assertThat(dmPartner.displayName()).isEqualTo("田中太郎");
            assertThat(dmPartner.avatarUrl()).isEqualTo("http://x/a.png");
        }

        @Test
        @DisplayName("AC-B3: 相手の displayName が null の場合は \"ユーザー\" にフォールバック")
        void AC_B3_displayName_null_フォールバック() {
            ChatChannelEntity dm = dmChannelWithId();
            ChatChannelMemberEntity partnerMember = ChatChannelMemberEntity.builder()
                    .channelId(DM_CH_ID).userId(PARTNER_ID).role(ChannelMemberRole.MEMBER).build();
            UserEntity partner = UserEntity.builder().email("p@example.com").build();
            ReflectionTestUtils.setField(partner, "id", PARTNER_ID);

            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(dm));
            given(memberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(memberRepository.countGroupedByChannelIds(anyList()))
                    .willReturn(List.of(count(DM_CH_ID, 2L)));
            given(memberRepository.findByChannelIdInAndUserIdNot(anyList(), eq(USER_ID)))
                    .willReturn(List.of(partnerMember));
            given(userRepository.findAllById(anyList())).willReturn(List.of(partner));
            given(chatMapper.toChannelResponse(dm)).willReturn(baseResponse(DM_CH_ID, "DM"));

            ChannelResponse.DmPartnerDto dmPartner =
                    chatChannelService.listMyChannels(USER_ID).get(0).getDmPartner();

            assertThat(dmPartner.displayName()).isEqualTo("ユーザー");
        }

        @Test
        @DisplayName("AC-B4: DM 以外（TEAM_PUBLIC）は dmPartner=null")
        void AC_B4_DM以外はdmPartnerがnull() {
            ChatChannelEntity team = teamChannelWithId();
            given(channelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(team));
            given(memberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(memberRepository.countGroupedByChannelIds(anyList()))
                    .willReturn(List.of(count(TEAM_CH_ID, 5L)));
            given(chatMapper.toChannelResponse(team)).willReturn(baseResponse(TEAM_CH_ID, "TEAM_PUBLIC"));

            ChannelResponse result = chatChannelService.listMyChannels(USER_ID).get(0);

            assertThat(result.getDmPartner()).isNull();
            // DM が無いので相手解決クエリは発行されない（N+1 ガード AC-B7 の一端）
            verify(memberRepository, never()).findByChannelIdInAndUserIdNot(anyList(), anyLong());
        }
    }

    // ========================================
    // getChannel（per-user 拡張 / AC-B5・B6）
    // ========================================
    @Nested
    @DisplayName("getChannel — per-user 拡張")
    class GetChannel {

        private ChatChannelEntity dmChannelWithId() {
            ChatChannelEntity ch = ChatChannelEntity.builder().channelType(ChannelType.DM).build();
            ReflectionTestUtils.setField(ch, "id", CHANNEL_ID);
            return ch;
        }

        private ChannelResponse base(String channelType) {
            return ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto(channelType, null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();
        }

        @Test
        @DisplayName("AC-B5: DM 詳細で memberCount / viewer / dmPartner を返す")
        void AC_B5_DM詳細でper_user拡張を返す() {
            ChatChannelEntity dm = dmChannelWithId();
            ChatChannelMemberEntity myMember = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(USER_ID).role(ChannelMemberRole.OWNER)
                    .unreadCount(1).build();
            ChatChannelMemberEntity partnerMember = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(PARTNER_ID).role(ChannelMemberRole.MEMBER).build();
            UserEntity partner = UserEntity.builder()
                    .email("p@example.com").displayName("佐藤花子").build();
            ReflectionTestUtils.setField(partner, "id", PARTNER_ID);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(dm));
            // 閲覧のメンバーシップ検証はガードへ委譲済み（モック既定＝通過＝当事者として扱う）。
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(myMember));
            given(memberRepository.countByChannelId(CHANNEL_ID)).willReturn(2L);
            given(memberRepository.findByChannelIdAndUserIdNot(CHANNEL_ID, USER_ID))
                    .willReturn(List.of(partnerMember));
            given(userRepository.findById(PARTNER_ID)).willReturn(Optional.of(partner));
            given(chatMapper.toChannelResponse(dm)).willReturn(base("DM"));

            ChannelResponse result = chatChannelService.getChannel(CHANNEL_ID, USER_ID);

            assertThat(result.getMemberCount()).isEqualTo(2);
            assertThat(result.getViewer()).isNotNull();
            assertThat(result.getViewer().unreadCount()).isEqualTo(1);
            assertThat(result.getDmPartner()).isNotNull();
            assertThat(result.getDmPartner().userId()).isEqualTo(PARTNER_ID);
            assertThat(result.getDmPartner().displayName()).isEqualTo("佐藤花子");
        }

        @Test
        @DisplayName("認可委譲: 閲覧判定は取得済みチャンネル実体でガードへ渡され、拒否されれば本文情報を返さない")
        void 閲覧判定はガードへ委譲される() {
            // 非メンバーにレスポンスを返すと、DM では相手の userId・表示名・アバターが漏れる。
            // メンバーシップ管理種別の閲覧可否は ChatChannelAccessGuardTest が検証し、
            // ここでは「実体を渡して委譲していること」と「拒否時に一切返さないこと」を担保する。
            ChatChannelEntity team = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(TEAM_ID).name("一般").build();
            ReflectionTestUtils.setField(team, "id", CHANNEL_ID);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(team));
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelMembership(team, USER_ID);

            assertThatThrownBy(() -> chatChannelService.getChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));

            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(channelAccessGuard).requireChannelMembership(captor.capture(), eq(USER_ID));
            assertThat(captor.getValue()).isSameAs(team);
            // 拒否された時点で相手情報の解決クエリは一切発行されない
            verify(memberRepository, never()).findByChannelIdAndUserIdNot(anyLong(), anyLong());
        }

        @Test
        @DisplayName("正常系: メンバーシップ非依存種別(村ロビー)はガードが素通しし viewer=null で返る")
        void 村ロビーは非メンバーでもviewerがnullで返る() {
            // VILLAGE_LOBBY / EVENT_CHAT は chat_channel_members を持たない横断スペースであり、
            // village / event ドメイン側で認可される。ガード側で素通しの扱いとなる種別。
            ChatChannelEntity lobby = ChatChannelEntity.builder()
                    .channelType(ChannelType.VILLAGE_LOBBY).name("井戸端").build();
            ReflectionTestUtils.setField(lobby, "id", CHANNEL_ID);

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(lobby));
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(memberRepository.countByChannelId(CHANNEL_ID)).willReturn(5L);
            given(chatMapper.toChannelResponse(lobby)).willReturn(base("VILLAGE_LOBBY"));

            ChannelResponse result = chatChannelService.getChannel(CHANNEL_ID, USER_ID);

            assertThat(result.getViewer()).isNull();
            assertThat(result.getMemberCount()).isEqualTo(5);
            assertThat(result.getDmPartner()).isNull();
        }

        @Test
        @DisplayName("AC-B6: 存在しないチャンネルは CHANNEL_NOT_FOUND（認可回帰）")
        void AC_B6_存在しないチャンネルはNOT_FOUND() {
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatChannelService.getChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_NOT_FOUND));
        }
    }

    // ========================================
    // startConversation
    // ========================================
    @Nested
    @DisplayName("startConversation")
    class StartConversation {

        private ChannelResponse stubResponse() {
            return ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("DM", null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();
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
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            given(userRepository.findById(INVITEE_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(saved);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toChannelResponse(saved)).willReturn(
                    ChannelResponse.builder()
                            .id(2L)
                            .identity(new ChannelResponse.ChannelIdentityDto("GROUP_DM", null, null))
                            .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                            .build());

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
        @DisplayName("認可委譲: DM 到達可否はガードへ委譲され、拒否されればチャンネルを作成しない")
        void DM到達可否の拒否でチャンネルを作らない() {
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(true);
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireDmDeliverable(
                            eq(USER_ID), eq(PARTNER_ID), eq(true), eq(DmReceiveFrom.ANYONE), any(), any());

            assertThatThrownBy(() ->
                    chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(channelRepository, never()).save(any(ChatChannelEntity.class));
        }

        @Test
        @DisplayName("認可委譲: ブロック状態・相手の受信範囲設定・照会 supplier が正しくガードへ渡る")
        void DM到達可否の判定材料がガードへ渡る() {
            // given: 相手は TEAM_MEMBERS_ONLY 設定、ブロックはしていない
            given(userBlockRepository.existsByBlockerIdAndBlockedId(PARTNER_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(PARTNER_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.TEAM_MEMBERS_ONLY)));
            given(channelRepository.findExistingDm(USER_ID, PARTNER_ID))
                    .willReturn(Optional.of(createDmChannel()));
            given(chatMapper.toChannelResponse(any(ChatChannelEntity.class))).willReturn(stubResponse());

            // when
            chatChannelService.startConversation(USER_ID, List.of(PARTNER_ID));

            // then: 相手 ID 由来の設定値がそのまま渡り、照会は supplier として遅延で渡される
            ArgumentCaptor<BooleanSupplier> sharesTeamCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
            ArgumentCaptor<BooleanSupplier> contactCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
            verify(channelAccessGuard).requireDmDeliverable(
                    eq(USER_ID), eq(PARTNER_ID), eq(false), eq(DmReceiveFrom.TEAM_MEMBERS_ONLY),
                    sharesTeamCaptor.capture(), contactCaptor.capture());

            // 共通チーム照会 supplier は role ドメインの正準（existsSharedTeam）を引く
            given(userRoleRepository.existsSharedTeam(USER_ID, PARTNER_ID)).willReturn(true);
            assertThat(sharesTeamCaptor.getValue().getAsBoolean()).isTrue();
            verify(userRoleRepository).existsSharedTeam(USER_ID, PARTNER_ID);

            // 連絡先照会 supplier は「相手の連絡先フォルダに呼出ユーザーが居るか」を引く
            given(chatContactFolderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    PARTNER_ID, FolderItemType.CONTACT, USER_ID)).willReturn(true);
            assertThat(contactCaptor.getValue().getAsBoolean()).isTrue();
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
            return ChannelResponse.builder()
                    .id(99L)
                    .identity(new ChannelResponse.ChannelIdentityDto("GROUP_DM", null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .build();
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
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(zimmer);
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA, memberB));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(INVITEE_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            given(memberRepository.existsByChannelIdAndUserId(zimmer.getId(), INVITEE_ID)).willReturn(false);
            given(chatMapper.toChannelResponse(zimmer)).willReturn(stubZimmerResponse());

            // when
            ChannelResponse result = chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false));

            // then
            assertThat(result).isNotNull();
            assertThat(result.getIdentity().channelType()).isEqualTo("GROUP_DM");
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
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(zimmer);
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(false);
            given(userRepository.findById(INVITEE_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
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
        @DisplayName("認可委譲: Kabine のメンバーシップをガードへ要求し、拒否されれば Zimmer を作らない")
        void Kabineのメンバーシップをガードへ委譲する() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelMember(CHANNEL_ID, USER_ID);

            // when & then
            assertThatThrownBy(() ->
                    chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(channelAccessGuard).requireChannelMember(CHANNEL_ID, USER_ID);
            verify(channelRepository, never()).save(any(ChatChannelEntity.class));
        }

        @Test
        @DisplayName("認可委譲: 招待対象ごとに DM 到達可否をガードへ要求し、拒否されればメンバーを追加しない")
        void 招待対象のDM到達可否をガードへ委譲する() {
            // given
            ChatChannelEntity kabine = createDmChannel();
            ChatChannelMemberEntity memberA = ChatChannelMemberEntity.builder()
                    .channelId(CHANNEL_ID).userId(USER_ID).build();

            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(kabine));
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(
                    ChatChannelEntity.builder().channelType(ChannelType.GROUP_DM).build());
            given(memberRepository.findByChannelIdOrderByJoinedAtAsc(CHANNEL_ID))
                    .willReturn(List.of(memberA));
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(userBlockRepository.existsByBlockerIdAndBlockedId(INVITEE_ID, USER_ID)).willReturn(true);
            given(userRepository.findById(INVITEE_ID))
                    .willReturn(Optional.of(createUser(DmReceiveFrom.ANYONE)));
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireDmDeliverable(
                            eq(USER_ID), eq(INVITEE_ID), eq(true), eq(DmReceiveFrom.ANYONE), any(), any());

            // when & then: 会話開始（Kabine/Zimmer）と同一の判定を通す
            assertThatThrownBy(() ->
                    chatChannelService.inviteToZimmer(CHANNEL_ID, USER_ID, buildRequest(false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        }
    }

    // ========================================
    // updateInquiryChannel per-scope 認可（Track2 第二陣 / 2026-05-29）
    // ========================================
    @Nested
    @DisplayName("updateInquiryChannel per-scope 認可")
    class UpdateInquiryChannelAuthz {

        private UpdateInquiryChannelRequest buildInquiryRequest(boolean isInquiry) {
            UpdateInquiryChannelRequest req = new UpdateInquiryChannelRequest();
            org.springframework.test.util.ReflectionTestUtils.setField(req, "isInquiryChannel", isInquiry);
            return req;
        }

        @Test
        @DisplayName("非権限者（当該チームの ADMIN でない）_COMMON_002 で遮断")
        void 非権限者_COMMON_002() {
            ChatChannelEntity channel = createChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() ->
                    chatChannelService.updateInquiryChannel(CHANNEL_ID, buildInquiryRequest(true), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            // 認可で弾かれるため保存は発生しない
            verify(channelRepository, never()).save(any());
        }

        @Test
        @DisplayName("当該チーム ADMIN_設定更新が通過する")
        void チームADMIN_通過() {
            ChatChannelEntity channel = createChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            // checkAdminOrAbove は void no-op（= 認可成功）
            given(channelRepository.findByTeamIdAndIsInquiryChannelTrue(TEAM_ID)).willReturn(Optional.empty());
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(channel)).willReturn(ChannelResponse.builder().build());

            chatChannelService.updateInquiryChannel(CHANNEL_ID, buildInquiryRequest(true), USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(channelRepository).save(channel);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_短絡でチーム ADMIN チェックを経ずに通過")
        void SYSTEM_ADMIN_短絡で通過() {
            ChatChannelEntity channel = createChannel();
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(channelRepository.findByTeamIdAndIsInquiryChannelTrue(TEAM_ID)).willReturn(Optional.empty());
            given(channelRepository.save(any(ChatChannelEntity.class))).willReturn(channel);
            given(chatMapper.toChannelResponse(channel)).willReturn(ChannelResponse.builder().build());

            chatChannelService.updateInquiryChannel(CHANNEL_ID, buildInquiryRequest(true), USER_ID);

            verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), anyString());
            verify(channelRepository).save(channel);
        }
    }
}
