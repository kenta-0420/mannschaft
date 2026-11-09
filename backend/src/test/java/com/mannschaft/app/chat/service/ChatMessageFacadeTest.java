package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatMessageFacade} の単体テスト（CMP-261105-1507）。
 *
 * <p>実物の {@link ChatChannelAccessGuard}（依存はモック）を通し、閲覧・投稿・編集・削除の認可と存在秘匿が
 * <b>tx 本体（{@link ChatMessageService}）より先に</b>走り、拒否されれば tx 本体に一切入らない
 * （本文を読み出さない・書き込まない）ことを検証する。大会連絡チャット（F08.7.1）の canView / canPost の配線も
 * ここで固定する（従来は {@code ChatMessageServiceTest} が担っていた）。</p>
 */
@ExtendWith(MockitoExtension.class)
// 同じテスト内で許可・拒否の両方の利用者を通すため、引数違いの呼び出しを許す
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatMessageFacade 単体テスト")
class ChatMessageFacadeTest {

    private static final Long CHANNEL_ID = 1L;
    private static final Long TARGET_CHANNEL_ID = 2L;
    private static final Long MESSAGE_ID = 10L;
    private static final Long SENDER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long TOURNAMENT_ID = 777L;

    @Mock
    private ChatMessageService messageService;
    @Mock
    private ChatChannelService channelService;
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

    @Mock
    private ChatInviteCardPreviewRefresher inviteCardPreviewRefresher;

    private ChatMessageFacade facade;

    @BeforeEach
    void setUp() {
        ChatChannelAccessGuard guard = new ChatChannelAccessGuard(channelRepository, memberRepository,
                accessControlService, tournamentContactAccessService, eventScopeAccessGuard, postingIdentityService);
        facade = new ChatMessageFacade(messageService, channelService, guard, inviteCardPreviewRefresher);
    }

    private ChatChannelEntity dm(Long id) {
        ChatChannelEntity ch = ChatChannelEntity.builder().channelType(ChannelType.DM).build();
        ReflectionTestUtils.setField(ch, "id", id);
        return ch;
    }

    private ChatChannelEntity tournament(Long id) {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(ChannelType.TOURNAMENT_CHAT).name("大会連絡").isPrivate(true)
                .sourceType("TOURNAMENT").sourceId(TOURNAMENT_ID).build();
        ReflectionTestUtils.setField(ch, "id", id);
        return ch;
    }

    /** チャンネル ID 指定系（Facade は ChatChannelService から引く）。 */
    private void channelById(ChatChannelEntity channel) {
        given(channelService.findChannelOrThrow(channel.getId())).willReturn(channel);
    }

    /** メッセージ ID 指定系（Facade はメッセージを引き、所属チャンネルをガードが引く）。 */
    private ChatMessageEntity messageIn(ChatChannelEntity channel, Long senderId) {
        ChatMessageEntity message = ChatMessageEntity.builder()
                .channelId(channel.getId()).senderId(senderId).body("本文").build();
        given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
        given(channelRepository.findById(channel.getId())).willReturn(Optional.of(channel));
        return message;
    }

    private void member(Long channelId, Long userId) {
        given(memberRepository.existsByChannelIdAndUserId(channelId, userId)).willReturn(true);
    }

    private static void assertDenied(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(expected));
    }

    @Nested
    @DisplayName("チャンネル ID 指定系（一覧・検索・アクティブスレッド・送信）")
    class ChannelIdEndpoints {

        @Test
        @DisplayName("他人の DM の一覧・検索・アクティブスレッドは不在と同一（CHAT_001）で、本文を読み出さない")
        void 他人のDMの閲覧は不在と同一() {
            channelById(dm(CHANNEL_ID));

            assertDenied(() -> facade.listMessages(CHANNEL_ID, OTHER_USER_ID, null, 10, null),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            assertDenied(() -> facade.searchMessages(CHANNEL_ID, "秘密", 10, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            assertDenied(() -> facade.getActiveThreads(CHANNEL_ID, OTHER_USER_ID, null, 10),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(messageService, never()).listMessages(any(), any(), any(), any(), any());
            verify(messageService, never()).searchMessages(any(), any(), any(), any());
            verify(messageService, never()).getActiveThreads(any(), any(), any(), any());
        }

        @Test
        @DisplayName("メンバーの一覧は認可の後に tx 本体へ進む")
        void メンバーは一覧を取得できる() {
            channelById(dm(CHANNEL_ID));
            member(CHANNEL_ID, SENDER_ID);

            facade.listMessages(CHANNEL_ID, SENDER_ID, null, 10, "after");

            InOrder order = inOrder(memberRepository, messageService);
            order.verify(memberRepository).existsByChannelIdAndUserId(CHANNEL_ID, SENDER_ID);
            order.verify(messageService).listMessages(CHANNEL_ID, SENDER_ID, null, 10, "after");
        }

        @Test
        @DisplayName("大会チャットの検索: 閲覧権限なしは不在と同一で、検索クエリを走らせない（本文の漏洩防止）")
        void 大会の検索は非権限者に漏らさない() {
            channelById(tournament(CHANNEL_ID));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .given(tournamentContactAccessService).checkView(any(), any(), any(), any());

            assertDenied(() -> facade.searchMessages(CHANNEL_ID, "秘密", 10, OTHER_USER_ID),
                    ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(messageService, never()).searchMessages(any(), any(), any(), any());
        }

        @Test
        @DisplayName("大会チャットの検索: 権限者は canView（正しい引数）を通って tx 本体へ進む")
        void 大会の検索は権限者に通す() {
            channelById(tournament(CHANNEL_ID));

            facade.searchMessages(CHANNEL_ID, "公開", 10, SENDER_ID);

            verify(tournamentContactAccessService).checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.CHAT, SENDER_ID);
            verify(messageService).searchMessages(CHANNEL_ID, "公開", 10, SENDER_ID);
        }

        @Test
        @DisplayName("送信: 他人の DM への投稿は不在と同一で、tx 本体に入らない")
        void 他人のDMへは投稿できない() {
            channelById(dm(CHANNEL_ID));
            SendMessageRequest req = new SendMessageRequest("本文", null, null, null);

            assertDenied(() -> facade.sendMessage(CHANNEL_ID, req, OTHER_USER_ID), ChatErrorCode.CHANNEL_NOT_FOUND);
            verify(messageService, never()).sendMessage(any(), any(), any());
        }

        @Test
        @DisplayName("送信: メンバーは認可の後に tx 本体へ進む（REST・WebSocket 共通の入口）")
        void メンバーは投稿できる() {
            channelById(dm(CHANNEL_ID));
            member(CHANNEL_ID, SENDER_ID);
            SendMessageRequest req = new SendMessageRequest("本文", null, null, null);

            facade.sendMessage(CHANNEL_ID, req, SENDER_ID);

            verify(messageService).sendMessage(CHANNEL_ID, req, SENDER_ID);
        }
    }

    @Nested
    @DisplayName("メッセージ ID 指定系（編集・削除・スレッド・ピン留め・転送）")
    class MessageIdEndpoints {

        @Test
        @DisplayName("編集: 存在を知り得るメンバーの他人は 403 CHAT_006、知り得ない第三者は不在と同一 CHAT_002")
        void 他人の投稿は編集できない() {
            ChatChannelEntity channel = dm(CHANNEL_ID);
            messageIn(channel, SENDER_ID);
            EditMessageRequest req = new EditMessageRequest("書き換え");

            member(CHANNEL_ID, OTHER_USER_ID);
            assertDenied(() -> facade.editMessage(MESSAGE_ID, req, OTHER_USER_ID), ChatErrorCode.MESSAGE_EDIT_DENIED);
            assertDenied(() -> facade.editMessage(MESSAGE_ID, req, 999L), ChatErrorCode.MESSAGE_NOT_FOUND);
            verify(messageService, never()).editMessage(any(), any(), any());
        }

        @Test
        @DisplayName("削除: 一般メンバーは 403 CHAT_007 で、tx 本体に入らない")
        void 一般メンバーは他人の投稿を削除できない() {
            ChatChannelEntity channel = dm(CHANNEL_ID);
            messageIn(channel, SENDER_ID);
            member(CHANNEL_ID, OTHER_USER_ID);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(Optional.of(
                    ChatChannelMemberEntity.builder().channelId(CHANNEL_ID).userId(OTHER_USER_ID)
                            .role(ChannelMemberRole.MEMBER).build()));

            assertDenied(() -> facade.deleteMessage(MESSAGE_ID, OTHER_USER_ID), ChatErrorCode.MESSAGE_DELETE_DENIED);
            verify(messageService, never()).deleteMessage(any(), any());
        verify(inviteCardPreviewRefresher, never()).refresh(any());
        }

        @Test
        @DisplayName("削除: チャンネル OWNER は他人の投稿を削除できる（F04.2 §5）")
        void チャンネルOWNERは削除できる() {
            ChatChannelEntity channel = dm(CHANNEL_ID);
            messageIn(channel, SENDER_ID);
            given(memberRepository.findByChannelIdAndUserId(CHANNEL_ID, OTHER_USER_ID)).willReturn(Optional.of(
                    ChatChannelMemberEntity.builder().channelId(CHANNEL_ID).userId(OTHER_USER_ID)
                            .role(ChannelMemberRole.OWNER).build()));

            facade.deleteMessage(MESSAGE_ID, OTHER_USER_ID);

            verify(messageService).deleteMessage(MESSAGE_ID, OTHER_USER_ID);
        // 削除後に、招待カードの一覧プレビュー文言をトランザクションの外で確定する
        verify(inviteCardPreviewRefresher).refresh(CHANNEL_ID);
        }

        @Test
        @DisplayName("スレッド: 大会チャットの閲覧権限なしは不在メッセージと同一で、スレッドを読み出さない")
        void 大会のスレッドは非権限者に漏らさない() {
            messageIn(tournament(CHANNEL_ID), SENDER_ID);
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .given(tournamentContactAccessService).checkView(any(), any(), any(), any());

            assertDenied(() -> facade.getThread(MESSAGE_ID, null, 10, OTHER_USER_ID), ChatErrorCode.MESSAGE_NOT_FOUND);
            verify(messageService, never()).getThread(any(), any(), any(), any());
        }

        @Test
        @DisplayName("ピン留め: 大会チャットは canPost を要求し、閲覧のみの利用者には大会の 403 を返す")
        void 大会のピン留めはcanPost() {
            messageIn(tournament(CHANNEL_ID), SENDER_ID);
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(tournamentContactAccessService).checkPost(any(), any(), any());

            assertDenied(() -> facade.togglePin(MESSAGE_ID, true, OTHER_USER_ID),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
            verify(messageService, never()).togglePin(any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("転送: 転送元を知り得ない利用者は不在メッセージと同一で、転送先を解決しない（転送元の実在を漏らさない）")
        void 転送元の秘匿は転送先の解決より先() {
            messageIn(dm(CHANNEL_ID), SENDER_ID);
            ForwardMessageRequest req = new ForwardMessageRequest(TARGET_CHANNEL_ID, null);

            assertDenied(() -> facade.forwardMessage(MESSAGE_ID, req, OTHER_USER_ID), ChatErrorCode.MESSAGE_NOT_FOUND);
            verify(channelService, never()).findChannelOrThrow(TARGET_CHANNEL_ID);
            verify(messageService, never()).forwardMessage(any(), any(), any());
        }

        @Test
        @DisplayName("転送: 転送先が大会チャンネルで canPost が無ければ投稿バイパスを弾く")
        void 転送先のcanPost無しは弾く() {
            messageIn(dm(CHANNEL_ID), SENDER_ID);
            member(CHANNEL_ID, SENDER_ID);
            channelById(tournament(TARGET_CHANNEL_ID));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(tournamentContactAccessService).checkPost(any(), any(), any());

            assertDenied(() -> facade.forwardMessage(MESSAGE_ID, new ForwardMessageRequest(TARGET_CHANNEL_ID, null),
                    SENDER_ID), TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
            verify(messageService, never()).forwardMessage(any(), any(), any());
        }
    }
}
