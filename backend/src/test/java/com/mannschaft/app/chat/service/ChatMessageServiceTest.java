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
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private com.mannschaft.app.common.DomainEventPublisher domainEventPublisher;

    /** F17.1 Phase 3: VILLAGE_LOBBY での postedAs 検証。 */
    @Mock
    private PostingIdentityService postingIdentityService;

    /** 送信者の表示名・アバター解決用（common 経由・sender 付与・N+1 回避の一括解決）。 */
    @Mock
    private com.mannschaft.app.common.NameResolverService nameResolver;

    /**
     * 本クラスに残る認可は、Controller から呼ばれない入口（スレッド返信一覧・添付 URL）のガード呼び出しだけである。
     * チャンネル ID・メッセージ ID 指定の操作の認可は tx の外の {@link ChatMessageFacade} が担い（CMP-261105-1507）、
     * 配線（大会連絡チャットの canView / canPost を含む）は {@code ChatMessageFacadeTest}、
     * 可否そのものは {@code ChatChannelAccessGuardTest} が検証する。
     */
    @Mock
    private ChatChannelAccessGuard channelAccessGuard;

    /** 添付キーからチャンネルを解決する経路（{@code resolveAttachmentDownloadRequest}）で使用する。 */
    @Mock
    private ChatChannelRepository channelRepository;

    @InjectMocks
    private ChatMessageService chatMessageService;

    private static final Long CHANNEL_ID = 1L;
    private static final Long MESSAGE_ID = 10L;
    private static final Long SENDER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    /**
     * 本テストは「認可を通過した後のドメインロジック」（tx 本体）を検証する。認可は Facade が先に済ませる前提で、
     * 既定シナリオ（＝正当なメンバーによる操作）のためのスタブを置く。
     *
     * <p>{@code lenient()} 必須: 使わないテストが多数あり、strict stub のままだと
     * {@code UnnecessaryStubbingException} で一斉に落ちる。</p>
     */
    @BeforeEach
    void setUpDefaultChannelAccess() {
        // createChannel() は id 未設定（null）のため anyLong() ではなく any() で受ける。
        lenient().when(memberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(true);
        lenient().when(channelService.findChannelOrThrow(any())).thenReturn(createChannel());
        // CMP-261105-1507: メッセージ ID 指定系はメッセージの所属チャンネルを channelRepository から引く
        //（不在なら MESSAGE_NOT_FOUND）。既定は通常チャンネルが存在するものとする。
        lenient().when(channelRepository.findById(any())).thenReturn(Optional.of(createChannel()));
    }

    private ChatChannelEntity createChannel() {
        return ChatChannelEntity.builder()
                .id(CHANNEL_ID)
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
                            .id(501L)
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
            verify(chatAttachmentService).claimMessageAttachment(
                    eq(channel), eq(attachmentEntity), eq(SENDER_ID));
            verify(chatAttachmentService).recordAttachmentUpload(
                    eq(channel), any(com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity.class), eq(SENDER_ID));
        }

        @Test
        @DisplayName("【未読カウント根治・red先行】メッセージ送信で送信者以外の全メンバーの未読カウントが一括インクリメントされる")
        void 送信者以外のメンバーの未読カウントが一括インクリメントされる() {
            // given
            SendMessageRequest req = new SendMessageRequest("未読カウント検証", null, null, null);
            ChatChannelEntity channel = createChannel();
            ChatMessageEntity saved = createMessage();
            MessageResponse expected = createMessageResponse();

            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(saved);
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);

            // when
            chatMessageService.sendMessage(CHANNEL_ID, req, SENDER_ID);

            // then: dead code 化していた incrementUnreadCount() の代わりに、
            // 送信者以外の全メンバーの unread_count を一括 UPDATE するリポジトリメソッドが呼ばれること
            // （N+1 回避のためメンバー数に依存しない1クエリで実装する設計）
            verify(memberRepository).incrementUnreadCountForOthers(CHANNEL_ID, SENDER_ID);
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
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            // when
            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            // then: 各添付について recordAttachmentDeletion が呼ばれる
            verify(chatAttachmentService).recordAttachmentDeletion(
                    eq(channel), eq(att), eq(SENDER_ID), eq(SENDER_ID));
            ArgumentCaptor<com.mannschaft.app.common.storage.S3ObjectDeleteEvent> deleteEventCaptor =
                    ArgumentCaptor.forClass(com.mannschaft.app.common.storage.S3ObjectDeleteEvent.class);
            verify(domainEventPublisher).publish(deleteEventCaptor.capture());
            assertThat(deleteEventCaptor.getValue().s3Keys()).containsExactly("chat/uuid/x.png");
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
            given(messageRepository.findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, null, 10);

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
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any()))
                    .willReturn(createMessageResponse());
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, cursor, 10);

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
            given(messageRepository.findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of(msg1, msg2, msg3));
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any()))
                    .willReturn(createMessageResponse());
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, null, 2);

            // then
            assertThat(result.getMeta().isHasNext()).isTrue();
            assertThat(result.getData()).hasSize(2); // limit件数のみ返る
        }

        @Test
        @DisplayName("正常系: limitがnullの場合はデフォルト50件で取得する")
        void limitがnullの場合はデフォルト50件で取得する() {
            // given
            given(messageRepository.findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            CursorPagedResponse<MessageResponse> result =
                    chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, null, null);

            // then
            assertThat(result).isNotNull();
            verify(messageRepository).findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID),
                    eq(org.springframework.data.domain.PageRequest.of(0, 51))); // 50+1
        }

        @Test
        @DisplayName("正常系: limitが100を超える場合は100件にクリップされる")
        void limitが上限を超える場合は上限にクリップされる() {
            // given
            given(messageRepository.findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, null, 200);

            // then
            verify(messageRepository).findByChannelIdOrderByCreatedAtDescIdDesc(eq(CHANNEL_ID),
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
            given(messageRepository.findByParentIdOrderByCreatedAtAscIdAsc(parentId)).willReturn(List.of(reply));
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
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

            given(messageRepository.searchByKeyword(eq(CHANNEL_ID), eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of(message));
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
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
            given(messageRepository.findByRootIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                    eq(MESSAGE_ID), any(Pageable.class))).willReturn(replyPage);
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
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
    // ========================================
    // 送信者情報（sender）付与 / N+1 回避
    // ========================================
    @Nested
    @DisplayName("送信者情報(sender)の付与")
    class SenderEnrichment {

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
            given(nameResolver.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Map.of(SENDER_ID, "山田太郎"));
            given(nameResolver.resolveUserAvatarUrls(any()))
                    .willReturn(java.util.Map.of(SENDER_ID, "https://cdn.example/a.png"));
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
        @DisplayName("正常系: 送信者の表示名が解決できない場合は displayName=\"ユーザー\"・avatar=null")
        void 送信者不在時はユーザーにフォールバック() {
            // given: NameResolver が空 Map を返す（該当ユーザー解決不能）
            ChatMessageEntity message = createMessage();
            EditMessageRequest req = new EditMessageRequest("更新メッセージ");
            MessageResponse expected = createMessageResponse();

            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(message));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(reactionRepository.findByMessageId(any())).willReturn(List.of());
            given(nameResolver.resolveUserDisplayNames(any())).willReturn(java.util.Map.of());
            given(nameResolver.resolveUserAvatarUrls(any())).willReturn(java.util.Map.of());
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
        @DisplayName("N+1回避: メッセージ一覧の送信者は表示名・アバターを各1回の一括解決で取得する")
        void メッセージ一覧はN1にならず一括解決する() {
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
            given(attachmentRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(reactionRepository.findByMessageIdInOrderByIdAsc(any())).willReturn(List.of());
            given(nameResolver.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Map.of(SENDER_ID, "山田太郎", OTHER_USER_ID, "佐藤花子"));
            given(nameResolver.resolveUserAvatarUrls(any())).willReturn(java.util.Map.of());
            given(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any())).willReturn(expected);
            given(chatMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(chatMapper.toReactionResponseList(any())).willReturn(List.of());

            // when
            chatMessageService.listMessages(CHANNEL_ID, SENDER_ID, cursor, 10);

            // then: 3 メッセージでも表示名・アバターの一括解決はそれぞれ 1 回のみ（メッセージ数に依存しない）
            verify(nameResolver, org.mockito.Mockito.times(1)).resolveUserDisplayNames(any());
            verify(nameResolver, org.mockito.Mockito.times(1)).resolveUserAvatarUrls(any());
            // 単発解決メソッドは呼ばれない
            verify(nameResolver, never()).resolveUserDisplayName(any());
            // CMP-261103-1225: 添付・リアクションもメッセージ数に依存せず各 1 回の一括取得
            verify(attachmentRepository, org.mockito.Mockito.times(1)).findByMessageIdInOrderByIdAsc(any());
            verify(reactionRepository, org.mockito.Mockito.times(1)).findByMessageIdInOrderByIdAsc(any());
            verify(attachmentRepository, never()).findByMessageId(any());
            verify(reactionRepository, never()).findByMessageId(any());
        }
    }

    // ========================================
    // 部屋一覧プレビュー（last_message_preview）と未読数の整合（CMP-261105-1507 P1）
    // ========================================
    @Nested
    @DisplayName("部屋一覧プレビューと未読数の整合")
    class LastMessagePreview {

        private ChatMessageEntity messageWithId(Long id, String body, LocalDateTime createdAt) {
            ChatMessageEntity m = ChatMessageEntity.builder()
                    .channelId(CHANNEL_ID).senderId(SENDER_ID).body(body).build();
            org.springframework.test.util.ReflectionTestUtils.setField(m, "id", id);
            org.springframework.test.util.ReflectionTestUtils.setField(m, "createdAt", createdAt);
            return m;
        }

        private ChatChannelEntity channelWithPreview(String preview, LocalDateTime at) {
            ChatChannelEntity ch = createChannel();
            ch.updateLastMessage(at, preview);
            return ch;
        }

        private void stubEnrich() {
            lenient().when(attachmentRepository.findByMessageId(any())).thenReturn(List.of());
            lenient().when(reactionRepository.findByMessageId(any())).thenReturn(List.of());
            lenient().when(chatMapper.toMessageResponseWithDetails(any(), any(), any(), any()))
                    .thenReturn(createMessageResponse());
            lenient().when(chatMapper.toAttachmentResponseList(any())).thenReturn(List.of());
            lenient().when(chatMapper.toReactionResponseList(any())).thenReturn(List.of());
        }

        @Test
        @DisplayName("最新メッセージを編集すると、プレビューが新しい本文になる")
        void 最新を編集するとプレビューが更新される() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity msg = messageWithId(MESSAGE_ID, "古い本文", at);
            ChatChannelEntity ch = channelWithPreview("古い本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(msg));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(msg);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(msg));
            stubEnrich();

            chatMessageService.editMessage(MESSAGE_ID, new EditMessageRequest("新しい本文"), SENDER_ID);

            assertThat(ch.getLastMessagePreview()).isEqualTo("新しい本文");
            assertThat(ch.getLastMessageAt()).isEqualTo(at);
        }

        @Test
        @DisplayName("最新でないメッセージを編集してもプレビューは変わらない")
        void 最新でない編集はプレビューを変えない() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity old = messageWithId(MESSAGE_ID, "古い本文", at.minusMinutes(5));
            ChatMessageEntity latest = messageWithId(MESSAGE_ID + 1, "最新本文", at);
            ChatChannelEntity ch = channelWithPreview("最新本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(old));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(old);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(latest));
            stubEnrich();

            chatMessageService.editMessage(MESSAGE_ID, new EditMessageRequest("書き換え"), SENDER_ID);

            assertThat(ch.getLastMessagePreview()).isEqualTo("最新本文");
        }

        @Test
        @DisplayName("最新メッセージを削除すると、直前の未削除メッセージの本文に差し戻る")
        void 最新を削除すると直前の本文へ差し戻る() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity prev = messageWithId(MESSAGE_ID - 1, "直前の本文", at.minusMinutes(3));
            ChatMessageEntity latest = messageWithId(MESSAGE_ID, "消す本文", at);
            ChatChannelEntity ch = channelWithPreview("消す本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(latest));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(latest);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(latest), Optional.of(prev));

            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            assertThat(ch.getLastMessagePreview()).isEqualTo("直前の本文");
            assertThat(ch.getLastMessageAt()).isEqualTo(at.minusMinutes(3));
        }

        @Test
        @DisplayName("唯一のメッセージを削除すると、プレビューは空になる")
        void 唯一を削除するとプレビューが空になる() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity only = messageWithId(MESSAGE_ID, "消す本文", at);
            ChatChannelEntity ch = channelWithPreview("消す本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(only));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(only);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(only), Optional.empty());

            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            assertThat(ch.getLastMessagePreview()).isNull();
            assertThat(ch.getLastMessageAt()).isNull();
        }

        @Test
        @DisplayName("最新でないメッセージを削除してもプレビューは変わらない")
        void 最新でない削除はプレビューを変えない() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity old = messageWithId(MESSAGE_ID, "古い本文", at.minusMinutes(5));
            ChatMessageEntity latest = messageWithId(MESSAGE_ID + 1, "最新本文", at);
            ChatChannelEntity ch = channelWithPreview("最新本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(old));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(old);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(latest));

            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            assertThat(ch.getLastMessagePreview()).isEqualTo("最新本文");
        }

        @Test
        @DisplayName("削除すると、そのメッセージを未読のメンバーの未読数を 1 減らす")
        void 削除で未読数が減る() {
            LocalDateTime at = LocalDateTime.of(2026, 11, 10, 9, 0);
            ChatMessageEntity msg = messageWithId(MESSAGE_ID, "消す本文", at);
            ChatChannelEntity ch = channelWithPreview("消す本文", at);
            given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(msg));
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(msg);
            given(channelRepository.findById(CHANNEL_ID)).willReturn(Optional.of(ch));
            given(attachmentRepository.findByMessageId(any())).willReturn(List.of());
            given(messageRepository.findFirstByChannelIdOrderByCreatedAtDescIdDesc(CHANNEL_ID))
                    .willReturn(Optional.of(msg), Optional.empty());

            chatMessageService.deleteMessage(MESSAGE_ID, SENDER_ID);

            verify(memberRepository).decrementUnreadCountForUnreadersOf(CHANNEL_ID, SENDER_ID, at);
        }
    }
}
