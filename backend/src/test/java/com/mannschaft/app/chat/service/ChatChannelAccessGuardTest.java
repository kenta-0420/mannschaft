package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.PostingIdentityService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatChannelAccessGuard} の単体テスト。
 *
 * <p>チャットドメインの認可判定はすべて本クラスに集約されているため、
 * 「誰がこの操作をしてよいか」の可否そのものはここで検証する。
 * Facade（{@link ChatChannelFacade} / {@link ChatMessageFacade}）側のテストは、
 * 認可が tx 本体より先に走り、拒否されれば tx 本体に入らないこと（配線）を検証する役割分担とする。</p>
 *
 * <p>エンティティを受け取る判定はパッケージ内からだけ呼ぶ（Service API にエンティティを公開しない）ため、
 * 本テストは {@code chat.service} パッケージに置く。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelAccessGuard 単体テスト")
class ChatChannelAccessGuardTest {

    @Mock
    private ChatChannelMemberRepository memberRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ChatChannelRepository channelRepository;

    @Mock
    private TournamentContactAccessService tournamentContactAccessService;

    @Mock
    private EventScopeAccessGuard eventScopeAccessGuard;

    @Mock
    private PostingIdentityService postingIdentityService;

    @InjectMocks
    private ChatChannelAccessGuard guard;

    private static final Long CHANNEL_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    private ChatChannelEntity channel(ChannelType type, Long teamId, Long organizationId) {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(type)
                .teamId(teamId)
                .organizationId(organizationId)
                .name("テストチャンネル")
                .build();
        ReflectionTestUtils.setField(ch, "id", CHANNEL_ID);
        return ch;
    }

    private ChatChannelMemberEntity member(ChannelMemberRole role) {
        return ChatChannelMemberEntity.builder()
                .channelId(CHANNEL_ID).userId(USER_ID).role(role).build();
    }

    /** {@link BusinessException} が指定のエラーコードで送出されることを表明する。 */
    private static void assertDenied(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(expected));
    }

    // ========================================
    // requireChannelMembership（存在秘匿つき・CMP-261105-1507）
    // ========================================
    @Nested
    @DisplayName("requireChannelMembership — メンバーシップ管理種別のみメンバー行を要求し、拒否は存在秘匿つき")
    class RequireChannelMembership {

        @Test
        @DisplayName("正常系: メンバーシップ管理種別（TEAM_PUBLIC）でメンバー行があれば通過する")
        void メンバーなら通過する() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> guard.requireChannelMembership(ch, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 公開チャンネルの同スコープ所属者（存在は既知）は CHANNEL_ACCESS_DENIED")
        void 公開チャンネルの同スコープ所属者は403() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireChannelMembership(ch, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: 他スコープの利用者は不在と同一の CHANNEL_NOT_FOUND")
        void 他スコープの利用者は不在と同一() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMembership(ch, USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 非公開チャンネルは同チームの一般所属者にも秘匿（CHANNEL_NOT_FOUND）")
        void 非公開チャンネルは同チームの一般所属者にも秘匿() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMembership(ch, USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(accessControlService, never()).isMember(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: DM の第三者は不在と同一の CHANNEL_NOT_FOUND（会話関係グラフの列挙を閉塞する）")
        void DMの第三者は不在と同一() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMembership(dm, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: userId が null なら CHANNEL_NOT_FOUND（メンバー照会せず fail-closed）")
        void userIdがnullなら拒否される() {
            ChatChannelEntity ch = channel(ChannelType.GROUP_DM, null, null);

            assertDenied(() -> guard.requireChannelMembership(ch, null),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("境界: メンバーシップ非依存種別（VILLAGE_LOBBY）は素通しし、メンバー照会もしない")
        void 村ロビーは素通しする() {
            ChatChannelEntity lobby = channel(ChannelType.VILLAGE_LOBBY, null, null);

            assertThatCode(() -> guard.requireChannelMembership(lobby, USER_ID)).doesNotThrowAnyException();
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("境界: メンバーシップ非依存種別（TOURNAMENT_CHAT）も素通しする")
        void 大会チャットは素通しする() {
            ChatChannelEntity tournament = channel(ChannelType.TOURNAMENT_CHAT, null, null);

            assertThatCode(() -> guard.requireChannelMembership(tournament, USER_ID))
                    .doesNotThrowAnyException();
            verifyNoInteractions(memberRepository);
        }
    }

    // ========================================
    // requireMembershipRowOrConceal / isAwareOfChannel
    // ========================================
    @Nested
    @DisplayName("requireMembershipRowOrConceal — 種別を問わずメンバー行を要求し、不在コードは引数で決まる")
    class RequireMembershipRowOrConceal {

        @Test
        @DisplayName("正常系: メンバー行があれば通過する")
        void メンバーなら通過する() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> guard.requireMembershipRowOrConceal(dm, USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: メッセージ ID 指定系では不在コードに MESSAGE_NOT_FOUND を使う")
        void 子リソースの不在コードを使う() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireMembershipRowOrConceal(dm, USER_ID, ChatErrorCode.MESSAGE_NOT_FOUND),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("境界: SYSTEM_ADMIN は存在を知り得るため 403（CHANNEL_ACCESS_DENIED）")
        void SYSTEM_ADMINは存在を知り得る() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertDenied(() -> guard.requireMembershipRowOrConceal(dm, USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("境界: スコープ ADMIN は非公開チャンネルの存在も知り得るため 403")
        void スコープADMINは非公開チャンネルも知り得る() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireMembershipRowOrConceal(ch, USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
    }

    // ========================================
    // requireChannelManagerRole
    // ========================================
    @Nested
    @DisplayName("requireChannelManagerRole — OWNER / ADMIN を要求する")
    class RequireChannelManagerRole {

        @Test
        @DisplayName("正常系: OWNER は通過する")
        void OWNERは通過する() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.OWNER)));

            assertThatCode(() -> guard.requireChannelManagerRole(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: ADMIN も通過する")
        void ADMINは通過する() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.ADMIN)));

            assertThatCode(() -> guard.requireChannelManagerRole(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 一般 MEMBER は拒否される（自己昇格・他人のロール変更を閉塞する）")
        void 一般MEMBERは拒否される() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));

            assertDenied(() -> guard.requireChannelManagerRole(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID,
                            ChatErrorCode.CHANNEL_ACCESS_DENIED),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: 存在を知り得ない非メンバーは不在と同一の CHANNEL_NOT_FOUND")
        void 非メンバーは不在と同一() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertDenied(() -> guard.requireChannelManagerRole(
                            channel(ChannelType.DM, null, null), USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("契約: 拒否時のエラーコードは引数で指定したものになる（経路ごとの API 契約に追随する）")
        void 拒否コードは引数で決まる() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));

            assertDenied(() -> guard.requireChannelManagerRole(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID,
                            ChatErrorCode.CHANNEL_ICON_PERMISSION_DENIED),
                    ChatErrorCode.CHANNEL_ICON_PERMISSION_DENIED);
        }
    }

    // ========================================
    // requireChannelAdminAccess
    // ========================================
    @Nested
    @DisplayName("requireChannelAdminAccess — スコープ ADMIN 以上 / DM はチャンネル OWNER")
    class RequireChannelAdminAccess {

        @Test
        @DisplayName("正常系: SYSTEM_ADMIN は短絡で通過し、スコープ判定もメンバー照会もしない")
        void SYSTEM_ADMINは短絡で通過する() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> guard.requireChannelAdminAccess(ch, USER_ID)).doesNotThrowAnyException();

            verify(accessControlService, never()).isAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("正常系: チームチャンネルは当該チームの ADMIN 以上なら通過する")
        void チームADMINは通過する() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatCode(() -> guard.requireChannelAdminAccess(ch, USER_ID)).doesNotThrowAnyException();
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("異常系: 公開チャンネルの同チーム一般所属者（存在は既知）は COMMON_002")
        void 同チーム一般所属者はCOMMON_002() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireChannelAdminAccess(ch, USER_ID), CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("異常系: 他チームの利用者は不在と同一の CHANNEL_NOT_FOUND")
        void 他チームの利用者は不在と同一() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelAdminAccess(ch, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("正常系: 組織チャンネル（teamId 無し）は組織スコープの ADMIN 判定に委ねる")
        void 組織チャンネルは組織ADMIN判定に委譲する() {
            ChatChannelEntity ch = channel(ChannelType.ORG_PRIVATE, null, ORG_ID);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> guard.requireChannelAdminAccess(ch, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: DM はチャンネル OWNER のみ通過する")
        void DMはOWNERのみ通過する() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.OWNER)));

            assertThatCode(() -> guard.requireChannelAdminAccess(dm, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: DM の一般 MEMBER は拒否される（他人の DM のグループ変換・削除を閉塞する）")
        void DMの一般MEMBERは拒否される() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));

            assertDenied(() -> guard.requireChannelAdminAccess(dm, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: DM の ADMIN ロールでも拒否される（DM の管理操作は OWNER に限る）")
        void DMのADMINロールは拒否される() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.ADMIN)));

            assertDenied(() -> guard.requireChannelAdminAccess(dm, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: DM の非メンバーは不在と同一の CHANNEL_NOT_FOUND")
        void DMの非メンバーは不在と同一() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertDenied(() -> guard.requireChannelAdminAccess(dm, USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    // ========================================
    // requireMessageDeletable（F04.2 §5 メッセージ削除権限）
    // ========================================
    @Nested
    @DisplayName("requireMessageDeletable — 投稿者本人 / チャンネル OWNER・ADMIN / スコープ ADMIN / SYSTEM_ADMIN")
    class RequireMessageDeletable {

        private ChatMessageEntity messageBy(Long senderId) {
            return ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(senderId).body("本文").build();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は通過し、モデレーション削除ではない（false）")
        void 本人は通過する() {
            assertThat(guard.requireMessageDeletable(
                    channel(ChannelType.DM, null, null), messageBy(USER_ID), USER_ID)).isFalse();
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("正常系: チャンネル OWNER は他人の投稿を削除できる（true）")
        void チャンネルOWNERは削除できる() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.OWNER)));

            assertThat(guard.requireMessageDeletable(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), messageBy(OTHER_USER_ID), USER_ID)).isTrue();
        }

        @Test
        @DisplayName("正常系: チーム ADMIN はチャンネル未参加でも削除できる")
        void チームADMINは削除できる() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(Optional.empty());
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThat(guard.requireMessageDeletable(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), messageBy(OTHER_USER_ID), USER_ID)).isTrue();
        }

        @Test
        @DisplayName("異常系: チーム DEPUTY_ADMIN は MANAGE_CHAT 登録前（CMP-261107-2134）のため他人の投稿を削除できない")
        void チームDEPUTY_ADMINは削除できない() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(Optional.empty());
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            // DEPUTY_ADMIN はスコープ管理者なので存在を知り得る（拒否は 403）
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireMessageDeletable(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), messageBy(OTHER_USER_ID), USER_ID),
                    ChatErrorCode.MESSAGE_DELETE_DENIED);
            verify(accessControlService, never()).hasAdminOrPermissionInScope(any(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系: チャンネルの一般 MEMBER は MESSAGE_DELETE_DENIED")
        void 一般MEMBERは拒否される() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            assertDenied(() -> guard.requireMessageDeletable(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), messageBy(OTHER_USER_ID), USER_ID),
                    ChatErrorCode.MESSAGE_DELETE_DENIED);
        }

        @Test
        @DisplayName("異常系: 他人の DM の第三者は不在と同一の MESSAGE_NOT_FOUND")
        void DMの第三者は不在と同一() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(Optional.empty());

            assertDenied(() -> guard.requireMessageDeletable(
                            channel(ChannelType.DM, null, null), messageBy(OTHER_USER_ID), USER_ID),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    // ========================================
    // requireChannelCreationScope
    // ========================================
    @Nested
    @DisplayName("requireChannelCreationScope — 作成者のスコープ所属を要求する")
    class RequireChannelCreationScope {

        @Test
        @DisplayName("正常系: 公開チームチャンネルはチーム所属（checkMembership）を要求する")
        void 公開チームチャンネルは所属を要求する() {
            guard.requireChannelCreationScope(ChannelType.TEAM_PUBLIC, TEAM_ID, null, false, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: isPrivate=true の公開種別はチーム ADMIN 以上を要求する")
        void 非公開指定はADMIN以上を要求する() {
            guard.requireChannelCreationScope(ChannelType.TEAM_PUBLIC, TEAM_ID, null, true, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: TEAM_PRIVATE は isPrivate 指定によらずチーム ADMIN 以上を要求する")
        void 非公開種別はADMIN以上を要求する() {
            guard.requireChannelCreationScope(ChannelType.TEAM_PRIVATE, TEAM_ID, null, false, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: 組織公開チャンネル（teamId 無し）は組織所属を要求する")
        void 組織公開チャンネルは組織所属を要求する() {
            guard.requireChannelCreationScope(ChannelType.ORG_PUBLIC, null, ORG_ID, false, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("正常系: 組織非公開チャンネルは組織 ADMIN 以上を要求する")
        void 組織非公開チャンネルはADMIN以上を要求する() {
            guard.requireChannelCreationScope(ChannelType.ORG_PRIVATE, null, ORG_ID, false, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("境界: teamId と organizationId の双方がある場合はチームスコープを優先する")
        void 双方指定時はチームを優先する() {
            guard.requireChannelCreationScope(ChannelType.TEAM_PUBLIC, TEAM_ID, ORG_ID, false, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("異常系: スコープ種別なのにスコープ識別子が無ければ CHANNEL_ACCESS_DENIED（fail-closed）")
        void スコープ識別子が無ければ拒否される() {
            assertDenied(() -> guard.requireChannelCreationScope(
                            ChannelType.TEAM_PUBLIC, null, null, false, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("境界: スコープを持たない種別（DM）は対象外として素通しする")
        void DMは対象外として素通しする() {
            assertThatCode(() -> guard.requireChannelCreationScope(
                    ChannelType.DM, null, null, false, USER_ID)).doesNotThrowAnyException();
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("境界: GROUP_DM も対象外として素通しする")
        void GROUP_DMは対象外として素通しする() {
            assertThatCode(() -> guard.requireChannelCreationScope(
                    ChannelType.GROUP_DM, null, null, true, USER_ID)).doesNotThrowAnyException();
            verifyNoInteractions(accessControlService);
        }
    }

    // ========================================
    // checkChannelViewAccess / checkChannelPostAccess（種別ごとの正準判定・存在秘匿つき）
    // ========================================
    @Nested
    @DisplayName("checkChannelViewAccess — 種別ごとの正準判定へ委譲し、非該当は不在コードと同一")
    class CheckChannelViewAccess {

        private static final Long TOURNAMENT_ID = 777L;

        private ChatChannelEntity tournament() {
            ChatChannelEntity ch = ChatChannelEntity.builder()
                    .channelType(ChannelType.TOURNAMENT_CHAT).name("大会連絡").isPrivate(true)
                    .sourceType("TOURNAMENT").sourceId(TOURNAMENT_ID).build();
            ReflectionTestUtils.setField(ch, "id", CHANNEL_ID);
            return ch;
        }

        @Test
        @DisplayName("大会チャット: canView 可なら通過し、大会の正準判定へ正しい引数で委譲する")
        void 大会はcanViewへ委譲する() {
            assertThatCode(() -> guard.checkChannelViewAccess(tournament(), USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(tournamentContactAccessService).checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.CHAT, USER_ID);
        }

        @Test
        @DisplayName("大会チャット: 閲覧権限なしは渡された不在コード（メッセージ ID 指定なら CHAT_002）と同一")
        void 大会の閲覧不可は不在と同一() {
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .given(tournamentContactAccessService).checkView(any(), any(), any(), any());

            assertDenied(() -> guard.checkChannelViewAccess(tournament(), USER_ID, ChatErrorCode.MESSAGE_NOT_FOUND),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("村ロビー: 当該村の現役メンバーでなければ不在と同一")
        void 村ロビーの非メンバーは不在と同一() {
            UUID villageId = UUID.randomUUID();
            ChatChannelEntity lobby = channel(ChannelType.VILLAGE_LOBBY, null, null);
            ReflectionTestUtils.setField(lobby, "villageId", villageId);
            given(postingIdentityService.isUserVillageMember(villageId, USER_ID)).willReturn(false);

            assertDenied(() -> guard.checkChannelViewAccess(lobby, USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("イベントチャット: イベントスコープのメンバーなら通過する")
        void イベントのメンバーは通過する() {
            ChatChannelEntity event = channel(ChannelType.EVENT_CHAT, null, null);
            ReflectionTestUtils.setField(event, "sourceId", 55L);
            given(eventScopeAccessGuard.isEventScopeMember(USER_ID, 55L)).willReturn(true);

            assertThatCode(() -> guard.checkChannelViewAccess(event, USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("メンバーシップ管理種別: 他スコープの利用者は不在と同一、公開チャンネルの同スコープ所属者は 403")
        void メンバーシップ管理種別は存在秘匿つき() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);
            assertDenied(() -> guard.checkChannelViewAccess(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    ChatErrorCode.CHANNEL_NOT_FOUND);

            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            assertDenied(() -> guard.checkChannelViewAccess(
                            channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("投稿: 大会チャットで閲覧はできるが投稿できない利用者は大会の 403 をそのまま返す")
        void 大会の投稿不可で閲覧可なら403() {
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(tournamentContactAccessService).checkPost(any(), any(), any());

            assertDenied(() -> guard.checkChannelPostAccess(tournament(), USER_ID, ChatErrorCode.CHANNEL_NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("投稿: 大会チャットで閲覧もできない利用者は不在と同一")
        void 大会の閲覧も不可なら不在と同一() {
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(tournamentContactAccessService).checkPost(any(), any(), any());
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .given(tournamentContactAccessService).checkView(any(), any(), any(), any());

            assertDenied(() -> guard.checkChannelPostAccess(tournament(), USER_ID, ChatErrorCode.MESSAGE_NOT_FOUND),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("ID 指定の投稿認可: チャンネルが不在なら CHAT_001（越境の拒否と同一）")
        void ID指定で不在ならCHAT_001() {
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertDenied(() -> guard.requireChannelPostAccess(CHANNEL_ID, USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("メッセージの所属チャンネルが不在（論理削除済み）なら、メッセージも不在として CHAT_002")
        void 所属チャンネル不在はメッセージ不在() {
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.empty());
            ChatMessageEntity message = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(USER_ID).body("本文").build();

            assertDenied(() -> guard.channelOfMessage(message), ChatErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    // ========================================
    // requireSelfJoinable（自己参加・存在秘匿つき）
    // ========================================
    @Nested
    @DisplayName("requireSelfJoinable — 公開スコープチャンネルのスコープ所属者だけが自己参加できる")
    class RequireSelfJoinable {

        @Test
        @DisplayName("正常系: 公開チームチャンネルのチーム所属者は通過する")
        void 公開チャンネルの所属者は通過する() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatCode(() -> guard.requireSelfJoinable(channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 他スコープの利用者は不在と同一（CHAT_001）")
        void 他スコープの利用者は不在と同一() {
            assertDenied(() -> guard.requireSelfJoinable(channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 他人の DM は不在と同一（招待制の種別差で実在を漏らさない）")
        void 他人のDMは不在と同一() {
            assertDenied(() -> guard.requireSelfJoinable(channel(ChannelType.DM, null, null), USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 非公開チャンネルを知り得るスコープ ADMIN でも招待制なので CHANNEL_ACCESS_DENIED")
        void 非公開チャンネルは招待制() {
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireSelfJoinable(channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null), USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: スコープ非所属の SYSTEM_ADMIN は存在を知り得るので COMMON_002")
        void スコープ非所属のSYSTEM_ADMINはCOMMON_002() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertDenied(() -> guard.requireSelfJoinable(channel(ChannelType.ORG_PUBLIC, null, ORG_ID), USER_ID),
                    CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("境界: 村ロビー等メンバーシップで判定しない種別は素通しする")
        void 村ロビーは素通しする() {
            assertThatCode(() -> guard.requireSelfJoinable(channel(ChannelType.VILLAGE_LOBBY, null, null), USER_ID))
                    .doesNotThrowAnyException();
            verifyNoInteractions(accessControlService);
        }
    }

    // ========================================
    // requireInquiryChannelAdmin（F10.7）
    // ========================================
    @Nested
    @DisplayName("requireInquiryChannelAdmin — 当該チームの ADMIN / SYSTEM_ADMIN のみ")
    class RequireInquiryChannelAdmin {

        @Test
        @DisplayName("正常系: チーム ADMIN は通過する")
        void チームADMINは通過する() {
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatCode(() -> guard.requireInquiryChannelAdmin(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 公開チャンネルの同チーム一般所属者は存在を知り得るので COMMON_002")
        void 同チーム一般所属者はCOMMON_002() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertDenied(() -> guard.requireInquiryChannelAdmin(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID), CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("異常系: 他チームの利用者は不在と同一（CHAT_001）")
        void 他チームの利用者は不在と同一() {
            assertDenied(() -> guard.requireInquiryChannelAdmin(
                    channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null), USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    // ========================================
    // requireMessageOwner
    // ========================================
    @Nested
    @DisplayName("requireMessageOwner — 送信者本人のみを許可する")
    class RequireMessageOwner {

        private ChatMessageEntity message(Long senderId) {
            return ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(senderId).body("本文").build();
        }

        @Test
        @DisplayName("正常系: 送信者本人は通過する")
        void 送信者本人は通過する() {
            assertThatCode(() -> guard.requireMessageOwner(dm(), message(USER_ID), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 存在を知り得るチャンネルメンバーの他人は MESSAGE_EDIT_DENIED")
        void 他人は拒否される() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(true);
            assertDenied(() -> guard.requireMessageOwner(dm(), message(USER_ID), OTHER_USER_ID),
                    ChatErrorCode.MESSAGE_EDIT_DENIED);
        }

        @Test
        @DisplayName("異常系: 存在を知り得ない第三者は不在と同一の MESSAGE_NOT_FOUND")
        void 第三者は不在と同一() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(false);
            assertDenied(() -> guard.requireMessageOwner(dm(), message(USER_ID), OTHER_USER_ID),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: userId が null なら MESSAGE_NOT_FOUND")
        void userIdがnullなら拒否される() {
            assertDenied(() -> guard.requireMessageOwner(dm(), message(USER_ID), null),
                    ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 送信者が null のメッセージは誰も編集できない")
        void 送信者nullのメッセージは拒否される() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);
            assertDenied(() -> guard.requireMessageOwner(dm(), message(null), USER_ID),
                    ChatErrorCode.MESSAGE_EDIT_DENIED);
        }

        private ChatChannelEntity dm() {
            return channel(ChannelType.DM, null, null);
        }
    }
}
