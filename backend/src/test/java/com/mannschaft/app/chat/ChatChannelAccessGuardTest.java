package com.mannschaft.app.chat;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.service.ChatChannelAccessGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * 各業務サービス（{@code ChatChannelService} / {@code ChatMemberService} /
 * {@code ChatMessageService} / {@code ChatAttachmentService}）側のテストは、
 * ガードを<b>正しい引数で呼んでいるか</b>（委譲）を検証する役割分担とする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelAccessGuard 単体テスト")
class ChatChannelAccessGuardTest {

    @Mock
    private ChatChannelMemberRepository memberRepository;

    @Mock
    private AccessControlService accessControlService;

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
    // requireChannelMembership
    // ========================================
    @Nested
    @DisplayName("requireChannelMembership — メンバーシップ管理種別のみメンバー行を要求する")
    class RequireChannelMembership {

        @Test
        @DisplayName("正常系: メンバーシップ管理種別（TEAM_PUBLIC）でメンバー行があれば通過する")
        void メンバーなら通過する() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> guard.requireChannelMembership(ch, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 非メンバーは CHANNEL_ACCESS_DENIED（TEAM_PUBLIC も対象）")
        void 非メンバーは拒否される() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, TEAM_ID, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMembership(ch, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: DM の第三者は CHANNEL_ACCESS_DENIED（会話関係グラフの列挙を閉塞する）")
        void DMの第三者は拒否される() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMembership(dm, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: userId が null なら CHANNEL_ACCESS_DENIED（メンバー照会せず fail-closed）")
        void userIdがnullなら拒否される() {
            ChatChannelEntity ch = channel(ChannelType.GROUP_DM, null, null);

            assertDenied(() -> guard.requireChannelMembership(ch, null),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
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
    // requireChannelMember
    // ========================================
    @Nested
    @DisplayName("requireChannelMember — 種別を問わずメンバー行を要求する")
    class RequireChannelMember {

        @Test
        @DisplayName("正常系: メンバー行があれば通過する")
        void メンバーなら通過する() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> guard.requireChannelMember(CHANNEL_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: メンバー行が無ければ CHANNEL_ACCESS_DENIED")
        void 非メンバーは拒否される() {
            given(memberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID)).willReturn(false);

            assertDenied(() -> guard.requireChannelMember(CHANNEL_ID, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: userId が null なら CHANNEL_ACCESS_DENIED")
        void userIdがnullなら拒否される() {
            assertDenied(() -> guard.requireChannelMember(CHANNEL_ID, null),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
            verifyNoInteractions(memberRepository);
        }
    }

    // ========================================
    // requireChannelManagerRole
    // ========================================
    @Nested
    @DisplayName("requireChannelManagerRole — OWNER / ADMIN を要求する")
    class RequireChannelManagerRole {

        @Test
        @DisplayName("正常系: OWNER は通過し、操作者のメンバー行が返る")
        void OWNERは通過する() {
            ChatChannelMemberEntity operator = member(ChannelMemberRole.OWNER);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(operator));

            ChatChannelMemberEntity result = guard.requireChannelManagerRole(
                    CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED);

            assertThat(result).isSameAs(operator);
        }

        @Test
        @DisplayName("正常系: ADMIN も通過する")
        void ADMINは通過する() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.ADMIN)));

            assertThatCode(() -> guard.requireChannelManagerRole(
                    CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 一般 MEMBER は拒否される（自己昇格・他人のロール変更を閉塞する）")
        void 一般MEMBERは拒否される() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));

            assertDenied(() -> guard.requireChannelManagerRole(
                            CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("異常系: 非メンバーは拒否される")
        void 非メンバーは拒否される() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertDenied(() -> guard.requireChannelManagerRole(
                            CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ACCESS_DENIED),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("契約: 拒否時のエラーコードは引数で指定したものになる（経路ごとの API 契約に追随する）")
        void 拒否コードは引数で決まる() {
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.of(member(ChannelMemberRole.MEMBER)));

            assertDenied(() -> guard.requireChannelManagerRole(
                            CHANNEL_ID, USER_ID, ChatErrorCode.CHANNEL_ICON_PERMISSION_DENIED),
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

            verify(accessControlService, never()).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("正常系: チームチャンネルは当該チームの checkAdminOrAbove に委ねる")
        void チームチャンネルはチームADMIN判定に委譲する() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);

            guard.requireChannelAdminAccess(ch, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verifyNoInteractions(memberRepository);
        }

        @Test
        @DisplayName("異常系: チーム ADMIN でなければ checkAdminOrAbove の例外がそのまま伝播する")
        void チーム非ADMINは拒否される() {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PRIVATE, TEAM_ID, null);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertDenied(() -> guard.requireChannelAdminAccess(ch, USER_ID), CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("正常系: 組織チャンネル（teamId 無し）は組織スコープの checkAdminOrAbove に委ねる")
        void 組織チャンネルは組織ADMIN判定に委譲する() {
            ChatChannelEntity ch = channel(ChannelType.ORG_PRIVATE, null, ORG_ID);

            guard.requireChannelAdminAccess(ch, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
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
        @DisplayName("異常系: DM の非メンバーは拒否される")
        void DMの非メンバーは拒否される() {
            ChatChannelEntity dm = channel(ChannelType.DM, null, null);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertDenied(() -> guard.requireChannelAdminAccess(dm, USER_ID),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
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
    // requireDmDeliverable
    // ========================================
    @Nested
    @DisplayName("requireDmDeliverable — 相手のブロック設定と DM 受信範囲設定を要求する")
    class RequireDmDeliverable {

        /** 呼ばれたかどうかを記録する BooleanSupplier。遅延評価の検証に使う。 */
        private BooleanSupplier recording(AtomicBoolean called, boolean value) {
            return () -> {
                called.set(true);
                return value;
            };
        }

        @Test
        @DisplayName("正常系: ANYONE は素通しする")
        void ANYONEは通過する() {
            assertThatCode(() -> guard.requireDmDeliverable(
                    USER_ID, OTHER_USER_ID, false, DmReceiveFrom.ANYONE, () -> false, () -> false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("遅延評価: ANYONE のときは共通チーム・連絡先の照会 supplier を評価しない")
        void ANYONEではsupplierを評価しない() {
            AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);
            AtomicBoolean contactCalled = new AtomicBoolean(false);

            guard.requireDmDeliverable(USER_ID, OTHER_USER_ID, false, DmReceiveFrom.ANYONE,
                    recording(sharesTeamCalled, true), recording(contactCalled, true));

            assertThat(sharesTeamCalled).isFalse();
            assertThat(contactCalled).isFalse();
        }

        @Test
        @DisplayName("異常系: 相手にブロックされていれば CHANNEL_ACCESS_DENIED（受信設定より先に判定する）")
        void ブロックされていれば拒否される() {
            AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);

            assertDenied(() -> guard.requireDmDeliverable(
                            USER_ID, OTHER_USER_ID, true, DmReceiveFrom.ANYONE,
                            recording(sharesTeamCalled, true), () -> true),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
            assertThat(sharesTeamCalled).isFalse();
        }

        @Test
        @DisplayName("異常系: ブロックは受信設定が TEAM_MEMBERS_ONLY で共通チームがあっても優先して拒否する")
        void ブロックは受信設定に優先する() {
            assertDenied(() -> guard.requireDmDeliverable(
                            USER_ID, OTHER_USER_ID, true, DmReceiveFrom.TEAM_MEMBERS_ONLY,
                            () -> true, () -> true),
                    ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("正常系: TEAM_MEMBERS_ONLY は共通チームがあれば通過する")
        void TEAM_MEMBERS_ONLYは共通チームありで通過する() {
            assertThatCode(() -> guard.requireDmDeliverable(
                    USER_ID, OTHER_USER_ID, false, DmReceiveFrom.TEAM_MEMBERS_ONLY,
                    () -> true, () -> false)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: TEAM_MEMBERS_ONLY で共通チームが無ければ DM_RECEIVE_RESTRICTED")
        void TEAM_MEMBERS_ONLYは共通チームなしで拒否される() {
            assertDenied(() -> guard.requireDmDeliverable(
                            USER_ID, OTHER_USER_ID, false, DmReceiveFrom.TEAM_MEMBERS_ONLY,
                            () -> false, () -> true),
                    ChatErrorCode.DM_RECEIVE_RESTRICTED);
        }

        @Test
        @DisplayName("遅延評価: TEAM_MEMBERS_ONLY では連絡先照会 supplier を評価しない")
        void TEAM_MEMBERS_ONLYでは連絡先supplierを評価しない() {
            AtomicBoolean contactCalled = new AtomicBoolean(false);

            guard.requireDmDeliverable(USER_ID, OTHER_USER_ID, false, DmReceiveFrom.TEAM_MEMBERS_ONLY,
                    () -> true, recording(contactCalled, true));

            assertThat(contactCalled).isFalse();
        }

        @Test
        @DisplayName("正常系: CONTACTS_ONLY は相手の連絡先に登録されていれば通過する")
        void CONTACTS_ONLYは連絡先登録ありで通過する() {
            assertThatCode(() -> guard.requireDmDeliverable(
                    USER_ID, OTHER_USER_ID, false, DmReceiveFrom.CONTACTS_ONLY,
                    () -> false, () -> true)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: CONTACTS_ONLY で連絡先未登録なら DM_RECEIVE_RESTRICTED")
        void CONTACTS_ONLYは連絡先未登録で拒否される() {
            assertDenied(() -> guard.requireDmDeliverable(
                            USER_ID, OTHER_USER_ID, false, DmReceiveFrom.CONTACTS_ONLY,
                            () -> true, () -> false),
                    ChatErrorCode.DM_RECEIVE_RESTRICTED);
        }

        @Test
        @DisplayName("遅延評価: CONTACTS_ONLY では共通チーム照会 supplier を評価しない")
        void CONTACTS_ONLYでは共通チームsupplierを評価しない() {
            AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);

            guard.requireDmDeliverable(USER_ID, OTHER_USER_ID, false, DmReceiveFrom.CONTACTS_ONLY,
                    recording(sharesTeamCalled, true), () -> true);

            assertThat(sharesTeamCalled).isFalse();
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
            assertThatCode(() -> guard.requireMessageOwner(message(USER_ID), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 他人は MESSAGE_EDIT_DENIED")
        void 他人は拒否される() {
            assertDenied(() -> guard.requireMessageOwner(message(USER_ID), OTHER_USER_ID),
                    ChatErrorCode.MESSAGE_EDIT_DENIED);
        }

        @Test
        @DisplayName("異常系: userId が null なら MESSAGE_EDIT_DENIED")
        void userIdがnullなら拒否される() {
            assertDenied(() -> guard.requireMessageOwner(message(USER_ID), null),
                    ChatErrorCode.MESSAGE_EDIT_DENIED);
        }

        @Test
        @DisplayName("異常系: 送信者が null のメッセージは誰も編集できない")
        void 送信者nullのメッセージは拒否される() {
            assertDenied(() -> guard.requireMessageOwner(message(null), USER_ID),
                    ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }
}
