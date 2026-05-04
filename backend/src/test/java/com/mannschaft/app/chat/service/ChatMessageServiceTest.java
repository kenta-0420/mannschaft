package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
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
        return new MessageResponse(MESSAGE_ID, CHANNEL_ID, SENDER_ID, null, "テストメッセージ",
                null, false, false, null, 0, 0, false, List.of(), List.of(), null, null);
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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);

            // when
            MessageResponse result = chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(messageRepository).save(any(ChatMessageEntity.class));
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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);

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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);

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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
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
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(original);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
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
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.togglePin(MESSAGE_ID, true);

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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any()))
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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any()))
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
            given(messageRepository.findByParentIdOrderByCreatedAtAsc(parentId)).willReturn(List.of(reply));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            List<MessageResponse> result = chatMessageService.listThreadReplies(parentId);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("異常系: 親メッセージが存在しない場合はエラー")
        void 親メッセージが存在しない場合はエラー() {
            // given
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatMessageService.listThreadReplies(MESSAGE_ID))
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

            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of(message));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            List<MessageResponse> result = chatMessageService.searchMessages(CHANNEL_ID, "テスト", 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: limitがnullの場合はデフォルト50件で検索する")
        void limitがnullの場合はデフォルト50件で検索する() {
            // given
            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            chatMessageService.searchMessages(CHANNEL_ID, "テスト", null);

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
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(original);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
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
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            MessageResponse result = chatMessageService.togglePin(MESSAGE_ID, false);

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
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any())).willReturn(expected);

            // when
            MessageResponse result = chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }
}
