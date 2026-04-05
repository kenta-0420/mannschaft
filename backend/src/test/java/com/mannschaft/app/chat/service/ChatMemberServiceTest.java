package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
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

    @InjectMocks
    private ChatMemberService chatMemberService;

    private static final Long CHANNEL_ID = 1L;
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
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).name("test").build();
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
                    .channelType(ChannelType.TEAM_PUBLIC).name("test").build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatMemberService.joinChannel(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.ALREADY_MEMBER));
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
            chatMemberService.removeMember(CHANNEL_ID, USER_ID);

            // then
            verify(memberRepository).deleteByChannelIdAndUserId(CHANNEL_ID, USER_ID);
        }

        @Test
        @DisplayName("異常系: オーナーは退出不可")
        void オーナーは退出不可() {
            // given
            ChatChannelMemberEntity member = createMember(ChannelMemberRole.OWNER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member));

            // when & then
            assertThatThrownBy(() -> chatMemberService.removeMember(CHANNEL_ID, USER_ID))
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
            assertThatThrownBy(() -> chatMemberService.removeMember(CHANNEL_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MEMBER_NOT_FOUND));
        }
    }

    // ========================================
    // changeRole
    // ========================================
    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("正常系: ロールを変更できる")
        void ロールを変更できる() {
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
            MemberResponse result = chatMemberService.changeRole(CHANNEL_ID, TARGET_USER_ID, req);

            // then
            assertThat(result.getRole()).isEqualTo("ADMIN");
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
            chatMemberService.addMembers(CHANNEL_ID, req);

            // then
            verify(memberRepository).save(any(ChatChannelMemberEntity.class));
        }
    }
}
