package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateInquiryChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.PostingIdentityService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatChannelFacade} の単体テスト（CMP-261105-1507）。
 *
 * <p>実物の {@link ChatChannelAccessGuard}（依存はモック）を通し、認可と存在秘匿が<b>tx 本体より先に</b>走り、
 * 拒否されれば tx 本体（{@link ChatChannelService} / {@link ChatMemberService}）に一切入らないことを検証する。
 * 判定そのものの網羅は {@code ChatChannelAccessGuardTest}、実 DB での応答の一致は
 * {@code ChatExistenceConcealmentContractIT} が担う。</p>
 */
@ExtendWith(MockitoExtension.class)
// 同じテスト内で許可・拒否の両方の利用者を通すため、引数違いの呼び出しを許す
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatChannelFacade 単体テスト")
class ChatChannelFacadeTest {

    private static final Long CHANNEL_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long TEAM_ID = 10L;

    @Mock
    private ChatChannelService channelService;
    @Mock
    private ChatMemberService memberService;
    @Mock
    private ChatChannelRepository channelRepository;
    @Mock
    private ChatChannelMemberRepository memberRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TournamentContactAccessService tournamentContactAccessService;
    @Mock
    private EventScopeAccessGuard eventScopeAccessGuard;
    @Mock
    private PostingIdentityService postingIdentityService;

    private ChatChannelFacade facade;

    @BeforeEach
    void setUp() {
        ChatChannelAccessGuard guard = new ChatChannelAccessGuard(channelRepository, memberRepository,
                accessControlService, tournamentContactAccessService, eventScopeAccessGuard, postingIdentityService);
        facade = new ChatChannelFacade(channelService, memberService, guard);
    }

    private ChatChannelEntity channel(ChannelType type, Long teamId) {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(type).teamId(teamId).name("テストチャンネル").build();
        ReflectionTestUtils.setField(ch, "id", CHANNEL_ID);
        given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(ch);
        return ch;
    }

    private void memberRow(Long userId, ChannelMemberRole role) {
        given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, userId)).willReturn(Optional.of(
                ChatChannelMemberEntity.builder().channelId(CHANNEL_ID).userId(userId).role(role).build()));
    }

    private static void assertDenied(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(expected));
    }

    @Nested
    @DisplayName("チャンネル詳細・管理操作")
    class ChannelOperations {

        @Test
        @DisplayName("getChannel: 他人の DM は不在と同一（CHAT_001）で、tx 本体に入らない")
        void 他人のDMの詳細は不在と同一() {
            channel(ChannelType.DM, null);

            assertDenied(() -> facade.getChannel(CHANNEL_ID, OTHER_USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(channelService, never()).getChannel(anyLong(), anyLong());
        }

        @Test
        @DisplayName("getChannel: メンバーは認可の後に tx 本体へ進む")
        void メンバーは詳細を取得できる() {
            channel(ChannelType.DM, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            facade.getChannel(CHANNEL_ID, USER_ID);

            InOrder order = inOrder(memberRepository, channelService);
            order.verify(memberRepository).existsByChannelIdAndUserId(CHANNEL_ID, USER_ID);
            order.verify(channelService).getChannel(CHANNEL_ID, USER_ID);
        }

        @Test
        @DisplayName("updateChannel: 公開チャンネルの同チーム一般所属者は COMMON_002 で、tx 本体に入らない")
        void 一般所属者は更新できない() {
            channel(ChannelType.TEAM_PUBLIC, TEAM_ID);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> facade.updateChannel(CHANNEL_ID, new UpdateChannelRequest("新しい名前", null, null), USER_ID),
                    CommonErrorCode.COMMON_002);
            verify(channelService, never()).updateChannel(any(), any(), any());
        }

        @Test
        @DisplayName("deleteChannel / archiveChannel: 他チームの利用者は不在と同一で、tx 本体に入らない")
        void 他チームの利用者は削除もアーカイブもできない() {
            channel(ChannelType.TEAM_PRIVATE, TEAM_ID);

            assertDenied(() -> facade.deleteChannel(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            assertDenied(() -> facade.archiveChannel(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            assertDenied(() -> facade.unarchiveChannel(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(channelService, never()).deleteChannel(any(), any());
            verify(channelService, never()).archiveChannel(any(), any());
            verify(channelService, never()).unarchiveChannel(any(), any());
        }

        @Test
        @DisplayName("archiveChannel: チーム ADMIN は認可の後に tx 本体へ進む")
        void チームADMINはアーカイブできる() {
            channel(ChannelType.TEAM_PUBLIC, TEAM_ID);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            facade.archiveChannel(CHANNEL_ID, USER_ID);

            verify(channelService).archiveChannel(CHANNEL_ID, USER_ID);
        }

        @Test
        @DisplayName("convertToGroup: DM の OWNER でない参加者は 403 で、tx 本体に入らない")
        void DMのMEMBERはグループ化できない() {
            channel(ChannelType.DM, null);
            memberRow(USER_ID, ChannelMemberRole.MEMBER);

            assertDenied(() -> facade.convertToGroup(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_ACCESS_DENIED);
            verify(channelService, never()).convertToGroup(any(), any());
        }

        @Test
        @DisplayName("inviteToZimmer: 当事者でなければ種別判定（tx 本体）より先に不在と同一で拒否する")
        void Zimmer招待は当事者確認が先() {
            channel(ChannelType.TEAM_PUBLIC, TEAM_ID); // DM でない種別でも、当事者でなければ種別の差は見せない

            assertDenied(() -> facade.inviteToZimmer(CHANNEL_ID, OTHER_USER_ID, new InviteToZimmerRequest()),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(channelService, never()).inviteToZimmer(any(), any(), any());
        }

        @Test
        @DisplayName("updateInquiryChannel: 他チームの利用者は不在と同一、チーム ADMIN は tx 本体へ進む")
        void 問い合わせ設定はチームADMINのみ() {
            channel(ChannelType.TEAM_PUBLIC, TEAM_ID);
            UpdateInquiryChannelRequest req = new UpdateInquiryChannelRequest();

            assertDenied(() -> facade.updateInquiryChannel(CHANNEL_ID, req, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(channelService, never()).updateInquiryChannel(any(), any(), any());

            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            facade.updateInquiryChannel(CHANNEL_ID, req, USER_ID);
            verify(channelService).updateInquiryChannel(CHANNEL_ID, req, USER_ID);
        }

        @Test
        @DisplayName("チャンネルが不在なら認可の前に CHAT_001（越境の拒否と同一）で、ガードも tx 本体も触らない")
        void 不在は認可より先に404() {
            given(channelService.findChannelOrThrow(CHANNEL_ID))
                    .willThrow(new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));

            assertDenied(() -> facade.deleteChannel(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            verifyNoInteractions(accessControlService, memberRepository);
            verify(channelService, never()).deleteChannel(any(), any());
        }
    }

    @Nested
    @DisplayName("メンバー操作")
    class MemberOperations {

        @Test
        @DisplayName("addMembers / changeRole: 一般 MEMBER は CHANNEL_ACCESS_DENIED で、tx 本体に入らない")
        void 一般MEMBERは管理操作できない() {
            channel(ChannelType.TEAM_PRIVATE, TEAM_ID);
            memberRow(USER_ID, ChannelMemberRole.MEMBER);

            assertDenied(() -> facade.addMembers(CHANNEL_ID, USER_ID, new AddMemberRequest(List.of(OTHER_USER_ID))),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
            assertDenied(() -> facade.changeRole(CHANNEL_ID, OTHER_USER_ID, new ChangeRoleRequest("ADMIN"), USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
            verifyNoInteractions(memberService);
        }

        @Test
        @DisplayName("addMembers: チャンネル OWNER は認可の後に tx 本体へ進む")
        void OWNERはメンバーを追加できる() {
            channel(ChannelType.TEAM_PRIVATE, TEAM_ID);
            memberRow(USER_ID, ChannelMemberRole.OWNER);
            AddMemberRequest req = new AddMemberRequest(List.of(OTHER_USER_ID));

            facade.addMembers(CHANNEL_ID, USER_ID, req);

            verify(memberService).addMembers(CHANNEL_ID, USER_ID, req);
        }

        @Test
        @DisplayName("removeMember: 自分の退出はメンバーなら進み、非メンバーは不在と同一")
        void 自分の退出はメンバーのみ() {
            channel(ChannelType.DM, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);
            facade.removeMember(CHANNEL_ID, USER_ID, USER_ID);
            verify(memberService).removeMember(CHANNEL_ID, USER_ID, USER_ID);

            assertDenied(() -> facade.removeMember(CHANNEL_ID, OTHER_USER_ID, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(memberService, never()).removeMember(CHANNEL_ID, OTHER_USER_ID, OTHER_USER_ID);
        }

        @Test
        @DisplayName("joinChannel: 他人の DM は不在と同一で、tx 本体に入らない")
        void 他人のDMには参加できない() {
            channel(ChannelType.DM, null);

            assertDenied(() -> facade.joinChannel(CHANNEL_ID, OTHER_USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            verifyNoInteractions(memberService);
        }

        @Test
        @DisplayName("joinChannel: 公開チャンネルのチーム所属者は事前判定を通り、tx 本体（行ロックつき再確認）へ進む")
        void 公開チャンネルの所属者は参加できる() {
            channel(ChannelType.TEAM_PUBLIC, TEAM_ID);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            facade.joinChannel(CHANNEL_ID, USER_ID);

            verify(memberService).joinChannel(CHANNEL_ID, USER_ID);
        }
    }
}
