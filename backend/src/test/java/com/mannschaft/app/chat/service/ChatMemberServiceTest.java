package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.UpdateMyChannelSettingsRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatMemberService} の単体テスト。
 * メンバー追加・除外・ロール変更・個人設定・既読処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMemberService 単体テスト")
class ChatMemberServiceTest {

    @Mock
    private ChatChannelMemberRepository memberRepository;

    @Mock
    private ChatChannelService channelService;

    @Mock
    private ChatMapper chatMapper;

    @Mock
    private ChatChannelEventPublisher eventPublisher;

    @Mock
    private com.mannschaft.app.common.AccessControlService accessControlService;

    /**
     * チャンネル内の管理権限判定は本ガードに集約されている。
     * ロール別の可否そのものは {@code ChatChannelAccessGuardTest} が検証し、
     * 本テストは「正しい引数でガードへ委譲しているか」を検証する。
     */
    @Mock
    private ChatChannelAccessGuard channelAccessGuard;

    @InjectMocks
    private ChatMemberService chatMemberService;

    private static final Long CHANNEL_ID = 1L;
    /** 認可根治 Wave6: 公開チャンネル自己参加のスコープ所属検証で使うチームID。 */
    private static final Long JOIN_TEAM_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long TARGET_USER_ID = 200L;

    private ChatChannelMemberEntity createMember(ChannelMemberRole role) {
        return ChatChannelMemberEntity.builder()
                .channelId(CHANNEL_ID)
                .userId(USER_ID)
                .role(role)
                .build();
    }

    // ========================================
    // joinChannel
    // ========================================
    @Nested
    @DisplayName("joinChannel")
    class JoinChannel {

        @Test
        @DisplayName("正常系: チャンネルに参加できる")
        void チャンネルに参加できる() {
            // given
            // 認可根治 Wave6: 公開チャンネルの自己参加は「当該チームのメンバーであること」を要求するため、
            // teamId を持つチャンネルにする（accessControlService.checkMembership は void モックで通過）。
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(JOIN_TEAM_ID).name("test").build();
            ChatChannelMemberEntity saved = createMember(ChannelMemberRole.MEMBER);
            MemberResponse expected = new MemberResponse(1L, CHANNEL_ID, USER_ID, "MEMBER",
                    0, null, false, false, null, null);

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(saved);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class))).willReturn(expected);

            // when
            MemberResponse result = chatMemberService.joinChannel(CHANNEL_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 既にメンバーの場合はエラー")
        void 既にメンバーの場合はエラー() {
            // given
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(JOIN_TEAM_ID).name("test").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatMemberService.joinChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.ALREADY_MEMBER));
        }

        @Test
        @DisplayName("異常系: 他人のDMへの自己参加は拒否される（認可根治Wave6）")
        void DMへの自己参加は拒否される() {
            // given: DM は当事者のみで構成される。第三者の自己参加を許すと会話に侵入できてしまう。
            ChatChannelEntity dm = ChatChannelEntity.builder()
                    .channelType(ChannelType.DM).name("dm").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(dm);

            // when & then
            assertThatThrownBy(() -> chatMemberService.joinChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(memberRepository, never()).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("異常系: 非公開チャンネルへの自己参加は拒否される（招待制・認可根治Wave6）")
        void 非公開チャンネルへの自己参加は拒否される() {
            // given
            ChatChannelEntity privateChannel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PRIVATE).teamId(JOIN_TEAM_ID)
                    .isPrivate(true).name("private").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(privateChannel);

            // when & then
            assertThatThrownBy(() -> chatMemberService.joinChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(memberRepository, never()).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("異常系: 公開チャンネルでもスコープ非メンバーは拒否される（認可根治Wave6）")
        void スコープ非メンバーの公開チャンネル参加は拒否される() {
            // given: checkMembership が COMMON_002 を投げる＝チーム非所属
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(JOIN_TEAM_ID).name("test").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, JOIN_TEAM_ID, "TEAM");

            // when & then
            assertThatThrownBy(() -> chatMemberService.joinChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(memberRepository, never()).save(any(ChatChannelMemberEntity.class));
        }
    }

    // ========================================
    // removeMember
    // ========================================
    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("正常系: メンバーを除外できる")
        void メンバーを除外できる() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));

            // when
            chatMemberService.removeMember(CHANNEL_ID, USER_ID, USER_ID);

            // then
            verify(memberRepository).deleteByChannelIdAndUserId(CHANNEL_ID, USER_ID);
            // F04.2.1 §3.10.1: kick イベントが発出されることを検証
            verify(eventPublisher).publishMemberKicked(CHANNEL_ID, USER_ID);
        }

        @Test
        @DisplayName("認可委譲: 自分自身の退出はメンバーシップのみを要求する（管理権限は要求しない）")
        void 自己退出はメンバーシップのみ要求する() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));

            // when
            chatMemberService.removeMember(CHANNEL_ID, USER_ID, USER_ID);

            // then
            verify(channelAccessGuard).requireChannelMember(CHANNEL_ID, USER_ID);
            verify(channelAccessGuard, never()).requireChannelManagerRole(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("認可委譲: 他人の除外は OWNER / ADMIN を要求し、拒否されれば削除しない")
        void 他人の除外は管理権限を要求する() {
            // given
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelManagerRole(
                            CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED);

            // when & then
            assertThatThrownBy(() -> chatMemberService.removeMember(CHANNEL_ID, TARGET_USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(memberRepository, never()).deleteByChannelIdAndUserId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("異常系: オーナーは退出不可")
        void オーナーは退出不可() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.OWNER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));

            // when & then
            assertThatThrownBy(() -> chatMemberService.removeMember(CHANNEL_ID, USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.OWNER_CANNOT_LEAVE));
        }

        @Test
        @DisplayName("異常系: メンバーが見つからない場合はエラー")
        void メンバーが見つからない場合はエラー() {
            // given
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatMemberService.removeMember(CHANNEL_ID, USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MEMBER_NOT_FOUND));
        }
    }

    // ========================================
    // changeRole（認可根治 Wave 1 束2: 操作者OWNER/ADMIN検証）
    // ========================================
    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("正常系: 管理権限を満たす操作者は他メンバーのロールを変更できる")
        void 管理権限のある操作者はロールを変更できる() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            ChangeRoleRequest req = new ChangeRoleRequest("ADMIN");
            MemberResponse expected = new MemberResponse(1L, CHANNEL_ID, TARGET_USER_ID, "ADMIN",
                    0, null, false, false, null, null);

            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class))).willReturn(expected);

            // when
            MemberResponse result = chatMemberService.changeRole(CHANNEL_ID, TARGET_USER_ID, req, USER_ID);

            // then
            assertThat(result.getRole()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("認可委譲: 対象者ではなく操作者の管理権限を、ロール変更より前に要求する")
        void 操作者の管理権限をガードへ委譲する() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            ChangeRoleRequest req = new ChangeRoleRequest("ADMIN");

            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class)))
                    .willReturn(new MemberResponse(1L, CHANNEL_ID, TARGET_USER_ID, "ADMIN",
                            0, null, false, false, null, null));

            // when
            chatMemberService.changeRole(CHANNEL_ID, TARGET_USER_ID, req, USER_ID);

            // then: 検証対象は対象者(TARGET_USER_ID)ではなく操作者(USER_ID)である
            verify(channelAccessGuard).requireChannelManagerRole(
                    CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("認可委譲: 管理権限が無ければロールは書き換わらない（自己昇格の閉塞）")
        void 管理権限が無ければロールは書き換わらない() {
            // given: 操作者(=対象者本人)が自己昇格を試みる
            ChangeRoleRequest req = new ChangeRoleRequest("OWNER");
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelManagerRole(
                            CHANNEL_ID, TARGET_USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED);

            // when & then
            assertThatThrownBy(() -> chatMemberService.changeRole(CHANNEL_ID, TARGET_USER_ID, req, TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));

            // 実際にロールが書き換わっていないこと（権限昇格が成立していないこと）
            verify(memberRepository, never()).save(any(ChatChannelMemberEntity.class));
        }
    }

    // ========================================
    // updateSettings
    // ========================================
    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("正常系: チャンネル個人設定を更新できる")
        void チャンネル個人設定を更新できる() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            ChannelSettingsRequest req = new ChannelSettingsRequest(true, true, "仕事");
            MemberResponse expected = new MemberResponse(1L, CHANNEL_ID, USER_ID, "MEMBER",
                    0, null, true, true, "仕事", null);

            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class))).willReturn(expected);

            // when
            MemberResponse result = chatMemberService.updateSettings(CHANNEL_ID, USER_ID, req);

            // then
            assertThat(result).isEqualTo(expected);
        }
    }

    // ========================================
    // updateMySettings (F04.2 Phase 11 第二陣 2-β)
    // ========================================
    @Nested
    @DisplayName("updateMySettings (Phase 11 2-β)")
    class UpdateMySettings {

        @Test
        @DisplayName("正常系: 自分のミュート・ピン・カテゴリを更新できる")
        void 自分の個人設定を更新できる() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            UpdateMyChannelSettingsRequest req = new UpdateMyChannelSettingsRequest();
            req.setIsMuted(true);
            req.setIsPinned(true);
            req.setCategory("プロジェクト");
            MemberResponse expected = new MemberResponse(1L, CHANNEL_ID, USER_ID, "MEMBER",
                    0, null, true, true, "プロジェクト", null);

            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class))).willReturn(expected);

            // when
            MemberResponse result = chatMemberService.updateMySettings(CHANNEL_ID, USER_ID, req);

            // then
            assertThat(result).isEqualTo(expected);
            assertThat(member.getIsMuted()).isTrue();
            assertThat(member.getIsPinned()).isTrue();
            assertThat(member.getCategory()).isEqualTo("プロジェクト");
        }

        @Test
        @DisplayName("正常系: 指定したフィールドのみ更新される（PATCH セマンティクス）")
        void 指定したフィールドのみ更新される() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            // 元値: isPinned=true / category="既存"
            member.setPinned(true);
            member.updateCategory("既存カテゴリ");

            UpdateMyChannelSettingsRequest req = new UpdateMyChannelSettingsRequest();
            req.setIsMuted(true); // muted のみ指定

            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);
            given(chatMapper.toMemberResponse(any(ChatChannelMemberEntity.class)))
                    .willReturn(new MemberResponse(1L, CHANNEL_ID, USER_ID, "MEMBER",
                            0, null, true, true, "既存カテゴリ", null));

            // when
            chatMemberService.updateMySettings(CHANNEL_ID, USER_ID, req);

            // then
            assertThat(member.getIsMuted()).isTrue();
            assertThat(member.getIsPinned()).isTrue(); // 既存値が温存される
            assertThat(member.getCategory()).isEqualTo("既存カテゴリ");
        }

        @Test
        @DisplayName("異常系: 自分がチャンネルメンバーでない場合は MEMBER_NOT_FOUND")
        void メンバーでない場合は例外() {
            // given
            UpdateMyChannelSettingsRequest req = new UpdateMyChannelSettingsRequest();
            req.setIsMuted(true);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatMemberService.updateMySettings(CHANNEL_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MEMBER_NOT_FOUND));
        }
    }

    // ========================================
    // markAsRead
    // ========================================
    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("正常系: 既読処理ができる")
        void 既読処理ができる() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.MEMBER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));
            given(memberRepository.save(any(ChatChannelMemberEntity.class))).willReturn(member);

            // when
            chatMemberService.markAsRead(CHANNEL_ID, USER_ID);

            // then
            verify(memberRepository).save(any(ChatChannelMemberEntity.class));
        }
    }

    // ========================================
    // addMembers
    // ========================================
    @Nested
    @DisplayName("addMembers")
    class AddMembers {

        @Test
        @DisplayName("正常系: 既存メンバーはスキップして新規メンバーのみ追加される")
        void 既存メンバーはスキップして新規メンバーのみ追加される() {
            // given
            Long newUser = 300L;
            Long existingUser = 400L;
            AddMemberRequest req = new AddMemberRequest(List.of(newUser, existingUser));
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).name("test").build();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, newUser)).willReturn(false);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, existingUser)).willReturn(true);
            given(memberRepository.save(any(ChatChannelMemberEntity.class)))
                    .willReturn(ChatChannelMemberEntity.builder().build());
            given(chatMapper.toMemberResponseList(any())).willReturn(List.of());

            // when
            chatMemberService.addMembers(CHANNEL_ID, USER_ID, req);

            // then
            verify(memberRepository).save(any(ChatChannelMemberEntity.class));
        }

        @Test
        @DisplayName("認可委譲: 操作者の管理権限をガードへ要求し、拒否されればメンバーを追加しない")
        void 管理権限をガードへ委譲する() {
            // given
            AddMemberRequest req = new AddMemberRequest(List.of(300L));
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).name("test").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .given(channelAccessGuard).requireChannelManagerRole(
                            CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED);

            // when & then
            assertThatThrownBy(() -> chatMemberService.addMembers(CHANNEL_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.CHANNEL_ACCESS_DENIED));
            verify(memberRepository, never()).save(any(ChatChannelMemberEntity.class));
        }
    }
}
