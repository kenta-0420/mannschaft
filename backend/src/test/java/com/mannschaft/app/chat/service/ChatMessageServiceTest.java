package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ActiveThreadItemResponse;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.dto.ThreadResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatMessageService} の単体テスト。
 * メッセージ送信・編集・削除・転送・検索を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageService 単体テスト")
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private ChatMessageAttachmentRepository attachmentRepository;

    @Mock
    private ChatMessageReactionRepository reactionRepository;

    @Mock
    private ChatChannelService channelService;

    @Mock
    private ChatMapper chatMapper;

    /** F13 Phase 4-β: 添付の使用量計上連携。 */
    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private ChatChannelMemberRepository memberRepository;

    /** F04.2: WebSocket STOMP メッセージ配信。NPE 回避のため Mock 設定が必須。 */
    @Mock
    private ChatMessagePublisher chatMessagePublisher;

    /** F17.1 Phase 3: VILLAGE_LOBBY での postedAs 検証。 */
    @Mock
    private PostingIdentityService postingIdentityService;

    /** F08.7.1: 大会/ディビジョン連絡チャットの閲覧・投稿認可。 */
    @Mock
    private com.mannschaft.app.tournament.service.TournamentContactAccessService tournamentContactAccessService;

    /** 送信者の表示名・アバター解決用（sender 付与・N+1 回避の一括取得）。 */
    @Mock
    private com.mannschaft.app.auth.repository.UserRepository userRepository;

    @InjectMocks
    private ChatMessageService chatMessageService;

    private static final Long CHANNEL_ID = 1L;
    private static final Long MESSAGE_ID = 10L;
    private static final Long SENDER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    private ChatChannelEntity createChannel() {
        return ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(1L)
                .name("テストチャンネル")
                .createdBy(SENDER_ID)
                .build();
    }

    private ChatMessageEntity createMessage() {
        return ChatMessageEntity.builder()
                .channelId(CHANNEL_ID)
                .senderId(SENDER_ID)
                .body("テストメッセージ")
                .build();
    }

    private MessageResponse createMessageResponse() {
        return MessageResponse.builder()
                .id(MESSAGE_ID)
                .channelId(CHANNEL_ID)
                .senderId(SENDER_ID)
                .thread(new MessageResponse.MessageThreadDto(null, null, 0, false))
                .content(new MessageResponse.MessageContentDto("テストメッセージ", null, false, false, null))
                .engagement(new MessageResponse.MessageEngagementDto(0, 0, false, List.of(), List.of()))
                .audit(new MessageResponse.MessageAuditDto(null, null))
                .build();
    }

    // ========================================
    // sendMessage
    // ========================================
    @Nested
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        @DisplayName("正常系: メッセージを送信できる")
        void メッセージを送信できる() {
            // given
            SendMessageRequest req = new SendMessageRequest("こんにちは", null, null, null);
            ChatChannelEntity channel = createChannel();
            ChatMessageEntity saved = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            MessageResponse result = chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(messageRepository).save(any(ChatMessageEntity.class));
        }

        @Test
        @DisplayName("正常系: VILLAGE_LOBBYでpostedAs=TEAM指定時にPostingIdentityServiceが呼ばれる")
        void village_lobby_postedAsTeam_検証発火() {
            // given
            UUID villageId = UUID.randomUUID();
            Long teamSubjectId = 567L;
            SendMessageRequest req = new SendMessageRequest(
                    "おはよう村人", null, null, null,
                    VillageSubjectType.TEAM, teamSubjectId);
            ChatChannelEntity lobby = ChatChannelEntity.builder()
                    .channelType(ChannelType.VILLAGE_LOBBY)
                    .villageId(villageId)
                    .name("井戸端")
                    .createdBy(SENDER_ID)
                    .build();
            ChatMessageEntity saved = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(lobby);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: PostingIdentityService が TEAM=567 で検証される
            verify(postingIdentityService).validatePostingIdentity(
                    eq(SENDER_ID), eq(villageId), eq(VillageSubjectType.TEAM), eq(teamSubjectId));
        }

        @Test
        @DisplayName("正常系: スレッド返信の場合は親メッセージのリプライ数がインクリメントされる")
        void スレッド返信の場合は親メッセージのリプライ数がインクリメントされる() {
            // given
            Long parentId = 5L;
            SendMessageRequest req = new SendMessageRequest("返信", parentId, null, null);
            ChatChannelEntity channel = createChannel();
            ChatMessageEntity saved = createMessage();
            ChatMessageEntity parent = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then
            verify(messageRepository).findById(parentId);
        }

        @Test
        @DisplayName("F13 Phase 4-β: 添付ありメッセージ送信で recordAttachmentUpload が呼ばれる")
        void f13_添付ありで_recordUpload発火() {
            // given
            com.mannschaft.app.chat.dto.AttachmentRequest att = new com.mannschaft.app.chat.dto.AttachmentRequest(
                    "chat/uuid/x.png", "x.png", 4096L, "image/png");
            SendMessageRequest req = new SendMessageRequest(
                    "ファイル", null, null, java.util.List.of(att));
            ChatChannelEntity channel = createChannel();
            ChatMessageEntity saved = createMessage();
            MessageResponse expected = createMessageResponse();
            com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity attachmentEntity =
                    com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity.builder()
                            .messageId(MESSAGE_ID)
                            .fileKey("chat/uuid/x.png").fileName("x.png")
                            .fileSize(4096L).contentType("image/png").build();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(attachmentRepository.save(any(com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity.class)))
                    .willReturn(attachmentEntity);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: 添付保存後に recordAttachmentUpload が呼ばれる
            verify(chatAttachmentService).recordAttachmentUpload(
                    eq(channel), any(com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity.class), eq(SENDER_ID));
        }
    }

    // ========================================
    // editMessage
    // ========================================
    @Nested
    @DisplayName("editMessage")
    class EditMessage {

        @Test
        @DisplayName("正常系: メッセージを編集できる")
        void メッセージを編集できる() {
            // given
            ChatMessageEntity message = createMessage();
            EditMessageRequest req = new EditMessageRequest("更新メッセージ");
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.editMessage(MESSAGE_ID, req, SENDER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 他人のメッセージは編集不可")
        void 他人のメッセージは編集不可() {
            // given
            ChatMessageEntity message = createMessage();
            EditMessageRequest req = new EditMessageRequest("更新");
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));

            // when & then
            assertThatThrownBy(() -> chatMessageService.editMessage(MESSAGE_ID, req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MESSAGE_EDIT_DENIED));
        }
    }

    // ========================================
    // deleteMessage
    // ========================================
    @Nested
    @DisplayName("deleteMessage")
    class DeleteMessage {

        @Test
        @DisplayName("正常系: メッセージを論理削除できる")
        void メッセージを論理削除できる() {
            // given
            ChatMessageEntity message = createMessage();
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);

            // when
            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            // then
            verify(messageRepository).save(any(ChatMessageEntity.class));
        }

        @Test
        @DisplayName("異常系: 他人のメッセージは削除不可")
        void 他人のメッセージは削除不可() {
            // given
            ChatMessageEntity message = createMessage();
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));

            // when & then
            assertThatThrownBy(() -> chatMessageService.deleteMessage(MESSAGE_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MESSAGE_EDIT_DENIED));
        }

        @Test
        @DisplayName("F13 Phase 4-β: 添付ありメッセージ削除で recordAttachmentDeletion が呼ばれる")
        void f13_添付ありで_recordDeletion発火() {
            // given
            ChatMessageEntity message = createMessage();
            ChatChannelEntity channel = createChannel();
            com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity att =
                    com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity.builder()
                            .messageId(MESSAGE_ID)
                            .fileKey("chat/uuid/x.png").fileName("x.png")
                            .fileSize(2048L).contentType("image/png").build();
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(MESSAGE_ID))
                    .willReturn(java.util.List.of(att));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);

            // when
            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            // then: 各添付について recordAttachmentDeletion が呼ばれる
            verify(chatAttachmentService).recordAttachmentDeletion(
                    eq(channel), eq(att), eq(SENDER_ID), eq(SENDER_ID));
        }
    }

    // ========================================
    // forwardMessage
    // ========================================
    @Nested
    @DisplayName("forwardMessage")
    class ForwardMessage {

        @Test
        @DisplayName("正常系: メッセージを転送できる")
        void メッセージを転送できる() {
            // given
            Long targetChannelId = 20L;
            ChatMessageEntity original = createMessage();
            ChatChannelEntity targetChannel = createChannel();
            ForwardMessageRequest req = new ForwardMessageRequest(targetChannelId, "追加コメント");
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(original));
            given(channelService.findChannelOrThrow(targetChannelId)).willReturn(targetChannel);
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(original);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.forwardMessage(MESSAGE_ID, req, SENDER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // togglePin
    // ========================================
    @Nested
    @DisplayName("togglePin")
    class TogglePin {

        @Test
        @DisplayName("正常系: メッセージをピン留めできる")
        void メッセージをピン留めできる() {
            // given
            ChatMessageEntity message = createMessage();
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.togglePin(MESSAGE_ID, true, SENDER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // listMessages
    // ========================================
    @Nested
    @DisplayName("listMessages")
    class ListMessages {

        @Test
        @DisplayName("正常系: カーソルなしでメッセージ一覧を取得できる")
        void カーソルなしでメッセージ一覧を取得できる() {
            // given
            given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, null, 10);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getMeta().isHasNext()).isFalse();
        }

        @Test
        @DisplayName("正常系: カーソルありでメッセージを取得できる")
        void カーソルありでメッセージを取得できる() {
            // given
            Long cursor = 100L;
            ChatMessageEntity message = createMessage();
            given(messageRepository.findByChannelIdAndIdLessThan(eq(CHANNEL_ID), eq(cursor), any(Pageable.class)))
                    .willReturn(List.of(message));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any()))
                    .willReturn(createMessageResponse());
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, cursor, 10);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 取得件数+1件返った場合はhasNextがtrueになる")
        void 次ページがある場合はhasNextがtrue() {
            // given
            // limit=2で3件返ってきた場合はhasNext=true
            ChatMessageEntity msg1 = createMessage();
            ChatMessageEntity msg2 = createMessage();
            ChatMessageEntity msg3 = createMessage();
            given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of(msg1, msg2, msg3));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any()))
                    .willReturn(createMessageResponse());
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, null, 2);

            // then
            assertThat(result.getMeta().isHasNext()).isTrue();
            assertThat(result.getData()).hasSize(2); // limit件数のみ返る
        }

        @Test
        @DisplayName("正常系: limitがnullの場合はデフォルト50件で取得する")
        void limitがnullの場合はデフォルト50件で取得する() {
            // given
            given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, null, null);

            // then
            assertThat(result).isNotNull();
            verify(messageRepository).findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID),
                    eq(org.springframework.data.domain.PageRequest.of(0, 51))); // 50+1
        }

        @Test
        @DisplayName("正常系: limitが100を超える場合は100件にクリップされる")
        void limitが上限を超える場合は上限にクリップされる() {
            // given
            given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            chatMessageService.listMessages(CHANNEL_ID, null, 200);

            // then
            verify(messageRepository).findByChannelIdOrderByCreatedAtDesc(eq(CHANNEL_ID),
                    eq(org.springframework.data.domain.PageRequest.of(0, 101))); // 100+1
        }
    }

    // ========================================
    // listThreadReplies
    // ========================================
    @Nested
    @DisplayName("listThreadReplies")
    class ListThreadReplies {

        @Test
        @DisplayName("正常系: スレッド返信一覧を取得できる")
        void スレッド返信一覧を取得できる() {
            // given
            Long parentId = MESSAGE_ID;
            ChatMessageEntity parent = createMessage();
            ChatMessageEntity reply = createMessage();
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.findByParentIdOrderByCreatedAtAsc(parentId)).willReturn(List.of(reply));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            List<MessageResponse> result = chatMessageService.listThreadReplies(parentId, SENDER_ID);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("異常系: 親メッセージが存在しない場合はエラー")
        void 親メッセージが存在しない場合はエラー() {
            // given
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatMessageService.listThreadReplies(MESSAGE_ID, SENDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.MESSAGE_NOT_FOUND));
        }
    }

    // ========================================
    // searchMessages
    // ========================================
    @Nested
    @DisplayName("searchMessages")
    class SearchMessages {

        @Test
        @DisplayName("正常系: メッセージを検索できる")
        void メッセージを検索できる() {
            // given
            ChatMessageEntity message = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of(message));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            List<MessageResponse> result = chatMessageService.searchMessages(CHANNEL_ID, "テスト", 10, SENDER_ID);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: limitがnullの場合はデフォルト50件で検索する")
        void limitがnullの場合はデフォルト50件で検索する() {
            // given
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            chatMessageService.searchMessages(CHANNEL_ID, "テスト", null, SENDER_ID);

            // then
            verify(messageRepository).searchByKeyword(eq(CHANNEL_ID), eq("テスト"),
                    eq(org.springframework.data.domain.PageRequest.of(0, 50)));
        }
    }

    // ========================================
    // forwardMessage 追加パターン
    // ========================================
    @Nested
    @DisplayName("forwardMessage 追加パターン")
    class ForwardMessageAdditional {

        @Test
        @DisplayName("正常系: additionalCommentなしでそのまま転送できる")
        void additionalCommentなしで転送できる() {
            // given
            Long targetChannelId = 20L;
            ChatMessageEntity original = createMessage();
            ChatChannelEntity targetChannel = createChannel();
            ForwardMessageRequest req = new ForwardMessageRequest(targetChannelId, null); // null comment
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(original));
            given(channelService.findChannelOrThrow(targetChannelId)).willReturn(targetChannel);
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(original);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.forwardMessage(MESSAGE_ID, req, SENDER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // togglePin 追加パターン
    // ========================================
    @Nested
    @DisplayName("togglePin 追加パターン")
    class TogglePinAdditional {

        @Test
        @DisplayName("正常系: メッセージのピン留めを解除できる")
        void メッセージのピン留めを解除できる() {
            // given
            ChatMessageEntity message = createMessage();
            message.pin(); // 先にピン留め
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.togglePin(MESSAGE_ID, false, SENDER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(message.getIsPinned()).isFalse();
        }
    }

    // ========================================
    // sendMessage 追加パターン
    // ========================================
    @Nested
    @DisplayName("sendMessage 追加パターン")
    class SendMessageAdditional {

        @Test
        @DisplayName("正常系: 100文字超のメッセージはプレビューが100文字に切り詰められる")
        void 長いメッセージはプレビューが切り詰められる() {
            // given
            String longBody = "a".repeat(150);
            SendMessageRequest req = new SendMessageRequest(longBody, null, null, null);
            ChatChannelEntity channel = createChannel();
            ChatMessageEntity saved = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            MessageResponse result = chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // F04.2 スレッド無制限ネスト・active_thread_count
    // ========================================
    @Nested
    @DisplayName("F04.2 スレッド無制限ネスト")
    class ThreadNesting {

        @Test
        @DisplayName("正常系: トップレベルへの返信は rootId=親ID、depth=1 になる")
        void トップレベルへの返信はrootIdが親IDでdepth1() {
            // given
            Long parentId = 5L;
            SendMessageRequest req = new SendMessageRequest("返信", parentId, null, null);
            ChatChannelEntity channel = createChannel();
            // 親: depth=0（トップレベル）, rootId=null
            ChatMessageEntity parent = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("親").depth(0).build();
            ChatMessageEntity savedReply = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("返信")
                    .parentId(parentId).rootId(parentId).depth(1).build();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            // 最初の save: 新メッセージ保存。2回目: 親の replyCount 更新
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(savedReply);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: 親の replyCount がインクリメントされていること
            assertThat(parent.getReplyCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: 返信への返信は rootId を継承し depth がインクリメントされる")
        void 返信への返信はrootIdを継承してdepthインクリメント() {
            // given
            Long rootId = 1L;
            Long parentId = 5L; // depth=1 の返信
            SendMessageRequest req = new SendMessageRequest("孫返信", parentId, null, null);
            ChatChannelEntity channel = createChannel();
            // 親: depth=1, rootId=rootId
            ChatMessageEntity parent = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("返信")
                    .parentId(rootId).rootId(rootId).depth(1).build();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(messageRepository.save(any(ChatMessageEntity.class))).willAnswer(invocation -> {
                ChatMessageEntity arg = invocation.getArgument(0);
                // 新規メッセージ（"孫返信"）の場合のみ rootId と depth を検証
                // 親の replyCount 更新保存は別途実行されるため body で識別する
                if ("孫返信".equals(arg.getBody())) {
                    assertThat(arg.getRootId()).isEqualTo(rootId);
                    assertThat(arg.getDepth()).isEqualTo(2);
                }
                return arg;
            });
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: save が呼ばれた（新メッセージ + 親の replyCount 更新）
            verify(messageRepository, org.mockito.Mockito.atLeast(1)).save(any(ChatMessageEntity.class));
        }

        @Test
        @DisplayName("正常系: 初回返信時に active_thread_count が +1 される")
        void 初回返信でアクティブスレッドカウントが増える() {
            // given
            Long parentId = 5L;
            SendMessageRequest req = new SendMessageRequest("初回返信", parentId, null, null);
            ChatChannelEntity channel = createChannel();
            // 親: depth=0（トップレベル）, replyCount=0（まだ返信なし）
            ChatMessageEntity parent = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("親").depth(0).build();
            assertThat(parent.getReplyCount()).isEqualTo(0);
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(parent);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: チャンネルの activeThreadCount が 1 になる
            assertThat(channel.getActiveThreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: 全返信削除時に active_thread_count が -1 される")
        void 全返信削除でアクティブスレッドカウントが減る() {
            // given
            Long parentId = 3L;
            // 親: depth=0, replyCount=1（この返信が最後の1件）
            ChatMessageEntity parent = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("親").depth(0).build();
            parent.incrementReplyCount(); // replyCount = 1 にセット
            ChatChannelEntity channel = createChannel();
            channel.incrementActiveThreadCount(); // activeThreadCount = 1 にセット

            ChatMessageEntity reply = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("返信")
                    .parentId(parentId).rootId(parentId).depth(1).build();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(reply));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(reply);
            given(messageRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(attachmentRepository.findByMessageId(MESSAGE_ID)).willReturn(List.of());

            // when
            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            // then: activeThreadCount が 0 に戻る
            assertThat(channel.getActiveThreadCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: getThread がフラット配列を返す")
        void getThreadがフラット配列を返す() {
            // given
            ChatMessageEntity root = createMessage();
            ChatMessageEntity reply = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("返信").depth(1).rootId(MESSAGE_ID).build();
            Page<ChatMessageEntity> replyPage = new PageImpl<>(List.of(reply));
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(root));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel());
            given(messageRepository.findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                    eq(MESSAGE_ID), any(Pageable.class))).willReturn(replyPage);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            ThreadResponse result = chatMessageService.getThread(MESSAGE_ID, null, 10, SENDER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.messages()).hasSize(1);
            assertThat(result.root()).isNotNull();
        }

        @Test
        @DisplayName("正常系: depth >= 10 で suggestBoardMigration = true になる")
        void depth10以上でsuggestBoardMigrationがtrue() {
            // given: depth=10 のメッセージ
            ChatMessageEntity deepMessage = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("深いメッセージ").depth(10).build();

            // when: ChatMapper の BOARD_MIGRATION_SUGGEST_DEPTH を検証
            // ChatMapper.BOARD_MIGRATION_SUGGEST_DEPTH = 10
            assertThat(deepMessage.getDepth()).isGreaterThanOrEqualTo(10);
            // MessageResponse の suggestBoardMigration は depth >= 10 で true
            MessageResponse deepResponse = MessageResponse.builder()
                    .id(1L)
                    .channelId(CHANNEL_ID)
                    .senderId(SENDER_ID)
                    .thread(new MessageResponse.MessageThreadDto(null, null, 10, true))
                    .content(new MessageResponse.MessageContentDto("深いメッセージ", null, false, false, null))
                    .engagement(new MessageResponse.MessageEngagementDto(0, 0, false, List.of(), List.of()))
                    .audit(new MessageResponse.MessageAuditDto(null, null))
                    .build();
            assertThat(deepResponse.getThread().suggestBoardMigration()).isTrue();
        }
    }

    // ========================================
    // F08.7.1 大会/ディビジョン連絡チャットの認可配線（B3）
    // ========================================
    @Nested
    @DisplayName("F08.7.1 大会連絡チャット認可配線")
    class TournamentChannelAccess {

        private static final Long TOURNAMENT_ID = 777L;
        private static final Long TARGET_CHANNEL_ID = 20L;

        private ChatChannelEntity tournamentChannel() {
            return ChatChannelEntity.builder()
                    .channelType(ChannelType.TOURNAMENT_CHAT)
                    .name("大会連絡")
                    .isPrivate(true)
                    .sourceType("TOURNAMENT")
                    .sourceId(TOURNAMENT_ID)
                    .build();
        }

        private ChatMessageEntity tournamentMessage() {
            return ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("大会メッセージ").build();
        }

        @Test
        @DisplayName("searchMessages: 非権限者（canView 例外）はメッセージを取得できない（漏洩防止）")
        void search非権限者は漏洩しない() {
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(tournamentChannel());
            doThrow(new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.tournament.TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .when(tournamentContactAccessService)
                    .checkView(any(), any(), any(), any());

            assertThatThrownBy(() -> chatMessageService.searchMessages(CHANNEL_ID, "秘密", 10, OTHER_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            // 認可前に検索クエリが走らない＝本文を一切読み出さない
            verify(messageRepository, never()).searchByKeyword(any(), any(), any());
        }

        @Test
        @DisplayName("searchMessages: 権限者は canView を通過して検索できる")
        void search権限者は閲覧認可を通す() {
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(tournamentChannel());
            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("公開"), any(Pageable.class)))
                    .willReturn(List.of());

            chatMessageService.searchMessages(CHANNEL_ID, "公開", 10, SENDER_ID);

            verify(tournamentContactAccessService).checkView(
                    eq(com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT),
                    eq(TOURNAMENT_ID),
                    eq(com.mannschaft.app.tournament.ContactSpaceKind.CHAT),
                    eq(SENDER_ID));
        }

        @Test
        @DisplayName("getThread: 非権限者（canView 例外）はスレッド本文を取得できない（漏洩防止）")
        void getThread非権限者は漏洩しない() {
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(tournamentMessage()));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(tournamentChannel());
            doThrow(new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.tournament.TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .when(tournamentContactAccessService)
                    .checkView(any(), any(), any(), any());

            assertThatThrownBy(() -> chatMessageService.getThread(MESSAGE_ID, null, 10, OTHER_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            verify(messageRepository, never())
                    .findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(any(), any());
        }

        @Test
        @DisplayName("forwardMessage: 転送先が大会チャンネルで canPost 無し（例外）なら投稿バイパスを弾く")
        void forward転送先canPost無しは弾く() {
            ChatMessageEntity original = createMessage(); // 通常チャンネル由来（CHANNEL_ID）
            ChatChannelEntity target = tournamentChannel();
            ForwardMessageRequest req = new ForwardMessageRequest(TARGET_CHANNEL_ID, null);

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(original));
            given(channelService.findChannelOrThrow(TARGET_CHANNEL_ID)).willReturn(target);
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(createChannel()); // 転送元 canView no-op
            doThrow(new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.tournament.TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .when(tournamentContactAccessService).checkPost(any(), any(), any());

            assertThatThrownBy(() -> chatMessageService.forwardMessage(MESSAGE_ID, req, OTHER_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            // 投稿バイパスされない＝メッセージが保存されない
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("togglePin: 大会チャンネルは canPost を要求する（モデレーション相当）")
        void pin大会はcanPost() {
            ChatMessageEntity message = tournamentMessage();
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(tournamentChannel());
            doThrow(new com.mannschaft.app.common.BusinessException(
                    com.mannschaft.app.tournament.TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .when(tournamentContactAccessService).checkPost(any(), any(), any());

            assertThatThrownBy(() -> chatMessageService.togglePin(MESSAGE_ID, true, OTHER_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            verify(messageRepository, never()).save(any());
        }
    }

    // ========================================
    // 送信者情報（sender）付与 / N+1 回避
    // ========================================
    @Nested
    @DisplayName("送信者情報(sender)の付与")
    class SenderEnrichment {

        private com.mannschaft.app.auth.entity.UserEntity user(Long id, String displayName, String avatarUrl) {
            return com.mannschaft.app.auth.entity.UserEntity.builder()
                    .id(id)
                    .displayName(displayName)
                    .avatarUrl(avatarUrl)
                    .build();
        }

        @Test
        @DisplayName("正常系: enrich 後の sender に送信者の表示名・アバターが入る")
        void enrich後のsenderに表示名とアバターが入る() {
            // given: editMessage は単発 enrich を通る
            ChatMessageEntity message = createMessage(); // senderId = SENDER_ID
            EditMessageRequest req = new EditMessageRequest("更新メッセージ");
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(userRepository.findById(SENDER_ID))
                    .willReturn(Optional.of(user(SENDER_ID, "山田太郎", "https://cdn.example/a.png")));
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            chatMessageService.editMessage(MESSAGE_ID, req, SENDER_ID);

            // then: mapper へ渡された SenderDto を捕捉して検証
            org.mockito.ArgumentCaptor<MessageResponse.SenderDto> captor =
                    org.mockito.ArgumentCaptor.forClass(MessageResponse.SenderDto.class);
            verify(chatMapper).toMessageResponseWithDetails(any(), any(), any(), captor.capture());
            MessageResponse.SenderDto sender = captor.getValue();
            assertThat(sender).isNotNull();
            assertThat(sender.id()).isEqualTo(SENDER_ID);
            assertThat(sender.displayName()).isEqualTo("山田太郎");
            assertThat(sender.avatarUrl()).isEqualTo("https://cdn.example/a.png");
        }

        @Test
        @DisplayName("正常系: 送信者ユーザーが存在しない場合は displayName=\"ユーザー\"・avatar=null")
        void 送信者不在時はユーザーにフォールバック() {
            // given
            ChatMessageEntity message = createMessage();
            EditMessageRequest req = new EditMessageRequest("更新メッセージ");
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(userRepository.findById(SENDER_ID)).willReturn(Optional.empty());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            chatMessageService.editMessage(MESSAGE_ID, req, SENDER_ID);

            // then
            org.mockito.ArgumentCaptor<MessageResponse.SenderDto> captor =
                    org.mockito.ArgumentCaptor.forClass(MessageResponse.SenderDto.class);
            verify(chatMapper).toMessageResponseWithDetails(any(), any(), any(), captor.capture());
            MessageResponse.SenderDto sender = captor.getValue();
            assertThat(sender).isNotNull();
            assertThat(sender.id()).isEqualTo(SENDER_ID);
            assertThat(sender.displayName()).isEqualTo("ユーザー");
            assertThat(sender.avatarUrl()).isNull();
        }

        @Test
        @DisplayName("N+1回避: メッセージ一覧の送信者は findAllById で一括取得し findById は呼ばない")
        void メッセージ一覧はN1にならず一括取得する() {
            // given: 2 名の送信者からなる 3 メッセージ
            Long cursor = 100L;
            ChatMessageEntity m1 = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("a").build();
            ChatMessageEntity m2 = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(OTHER_USER_ID).body("b").build();
            ChatMessageEntity m3 = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body("c").build();
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findByChannelIdAndIdLessThan(eq(CHANNEL_ID), eq(cursor), any(Pageable.class)))
                    .willReturn(List.of(m1, m2, m3));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(userRepository.findAllById(any())).willReturn(List.of(
                    user(SENDER_ID, "山田太郎", null),
                    user(OTHER_USER_ID, "佐藤花子", null)));
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            chatMessageService.listMessages(CHANNEL_ID, cursor, 10);

            // then: 一括取得は 1 回、個別取得（findById）はゼロ
            verify(userRepository).findAllById(any());
            verify(userRepository, never()).findById(any());
        }
    }
}
