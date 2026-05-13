package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ActiveThreadItemResponse;
import com.mannschaft.app.chat.dto.AttachmentRequest;
import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.dto.ThreadResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * チャットメッセージサービス。メッセージの送受信・編集・削除・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;
    private static final int PREVIEW_LENGTH = 100;

    private final ChatMessageRepository messageRepository;
    private final ChatMessageAttachmentRepository attachmentRepository;
    private final ChatMessageReactionRepository reactionRepository;
    private final ChatChannelService channelService;
    private final ChatMapper chatMapper;
    /** F13 Phase 4-β: 統合ストレージクォータ連携。添付の INSERT 時 / 論理削除時の使用量計上に使用。 */
    private final ChatAttachmentService chatAttachmentService;

    /**
     * チャンネルのメッセージ一覧を取得する（カーソルベースページネーション）。
     *
     * @param channelId チャンネルID
     * @param cursor    カーソル（メッセージID）。null の場合は最新から取得
     * @param limit     取得件数
     * @return カーソルページネーション付きメッセージレスポンス
     */
    public CursorPagedResponse<MessageResponse> listMessages(Long channelId, Long cursor, Integer limit) {
        int effectiveLimit = resolveLimit(limit);
        Pageable pageable = PageRequest.of(0, effectiveLimit + 1);

        List<ChatMessageEntity> messages;
        if (cursor != null) {
            messages = messageRepository.findByChannelIdAndIdLessThan(channelId, cursor, pageable);
        } else {
            messages = messageRepository.findByChannelIdOrderByCreatedAtDesc(channelId, pageable);
        }

        boolean hasNext = messages.size() > effectiveLimit;
        if (hasNext) {
            messages = messages.subList(0, effectiveLimit);
        }

        List<MessageResponse> responses = enrichMessages(messages);

        String nextCursor = hasNext && !messages.isEmpty()
                ? String.valueOf(messages.get(messages.size() - 1).getId())
                : null;

        return CursorPagedResponse.of(
                responses,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, effectiveLimit)
        );
    }

    /**
     * メッセージを送信する。
     *
     * @param channelId チャンネルID
     * @param request   送信リクエスト
     * @param senderId  送信者ユーザーID
     * @return 送信されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse sendMessage(Long channelId, SendMessageRequest request, Long senderId) {
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);

        // スレッドネスト計算: 親メッセージが存在する場合に rootId・depth を設定
        Long rootId = null;
        int depth = 0;
        if (request.getParentId() != null) {
            ChatMessageEntity parent = findMessageOrThrow(request.getParentId());
            // rootId: 親がルートなら親のID、親がネストなら親の rootId を継承
            rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
            depth = (parent.getDepth() != null ? parent.getDepth() : 0) + 1;
        }

        ChatMessageEntity message = ChatMessageEntity.builder()
                .channelId(channelId)
                .senderId(senderId)
                .parentId(request.getParentId())
                .rootId(rootId)
                .depth(depth)
                .body(request.getBody())
                .scheduledAt(request.getScheduledAt())
                .build();

        ChatMessageEntity saved = messageRepository.save(message);

        // 親メッセージの返信数をインクリメント + active_thread_count 管理
        if (request.getParentId() != null) {
            ChatMessageEntity parent = findMessageOrThrow(request.getParentId());
            parent.incrementReplyCount();
            // 初回返信（depth==0のルートへの返信数が1になった）場合、チャンネルのアクティブスレッド数をインクリメント
            if (parent.isRootMessage() && parent.getReplyCount() == 1) {
                channel.incrementActiveThreadCount();
            }
            messageRepository.save(parent);
        }

        // 添付ファイルを保存（F13 Phase 4-β: 同時に StorageQuotaService.recordUpload を発火）
        List<AttachmentResponse> attachmentResponses = saveAttachments(
                saved.getId(), request.getAttachments(), channel, senderId);

        // チャンネルの最終メッセージ情報を更新
        String preview = request.getBody().length() > PREVIEW_LENGTH
                ? request.getBody().substring(0, PREVIEW_LENGTH)
                : request.getBody();
        channel.updateLastMessage(LocalDateTime.now(), preview);

        // チャンネルメンバーにリアルタイム通知（送信者自身を除く）
        // NOTE: チャンネルメンバー一覧取得はChannelMemberRepository連携後に拡張
        // 現時点ではNotificationHelperで通知レコード作成+WebSocket配信
        // 未読カウントのインクリメントはNotificationService側で管理

        log.info("メッセージ送信完了: messageId={}, channelId={}, senderId={}", saved.getId(), channelId, senderId);
        return chatMapper.toMessageResponseWithDetails(saved, attachmentResponses, List.of());
    }

    /**
     * メッセージを編集する。
     *
     * @param messageId メッセージID
     * @param request   編集リクエスト
     * @param userId    操作ユーザーID
     * @return 編集されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse editMessage(Long messageId, EditMessageRequest request, Long userId) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        validateMessageOwner(message, userId);

        message.editBody(request.getBody());
        ChatMessageEntity saved = messageRepository.save(message);

        log.info("メッセージ編集完了: messageId={}", messageId);
        return enrichMessage(saved);
    }

    /**
     * メッセージを削除する（論理削除）。
     *
     * @param messageId メッセージID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        validateMessageOwner(message, userId);

        // F13 Phase 4-β: 論理削除前に添付ファイル一覧を取得し、各添付の使用量を減算
        List<ChatMessageAttachmentEntity> attachments = attachmentRepository.findByMessageId(messageId);
        if (!attachments.isEmpty()) {
            ChatChannelEntity channel = channelService.findChannelOrThrow(message.getChannelId());
            for (ChatMessageAttachmentEntity attachment : attachments) {
                chatAttachmentService.recordAttachmentDeletion(
                        channel, attachment, userId, message.getSenderId());
            }
        }

        message.softDelete();
        messageRepository.save(message);

        // 返信削除後: 親の replyCount を減らし、0 になった場合は active_thread_count をデクリメント
        if (message.getParentId() != null) {
            messageRepository.findById(message.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                if (parent.isRootMessage() && parent.getReplyCount() == 0) {
                    ChatChannelEntity parentChannel = channelService.findChannelOrThrow(parent.getChannelId());
                    parentChannel.decrementActiveThreadCount();
                }
                messageRepository.save(parent);
            });
        }

        log.info("メッセージ削除完了: messageId={}", messageId);
    }

    /**
     * スレッド返信一覧を取得する（後方互換のため残す）。
     *
     * @param parentId 親メッセージID
     * @return メッセージレスポンスリスト
     */
    public List<MessageResponse> listThreadReplies(Long parentId) {
        findMessageOrThrow(parentId);
        List<ChatMessageEntity> replies = messageRepository.findByParentIdOrderByCreatedAtAsc(parentId);
        return enrichMessages(replies);
    }

    /**
     * スレッドの全返信をフラット取得する（無制限ネスト対応）。
     *
     * @param messageId ルートメッセージID
     * @param cursor    カーソル（ページネーション用）
     * @param limit     取得件数
     * @return スレッドレスポンス
     */
    public ThreadResponse getThread(Long messageId, String cursor, Integer limit) {
        ChatMessageEntity root = findMessageOrThrow(messageId);
        int effectiveLimit = resolveLimit(limit);

        // カーソルをページ番号に変換（簡易実装: cursor は "page_N" 形式）
        int page = parseCursorAsPage(cursor);
        Pageable pageable = PageRequest.of(page, effectiveLimit);

        Page<ChatMessageEntity> replyPage = messageRepository
                .findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(messageId, pageable);

        List<MessageResponse> messages = enrichMessages(replyPage.getContent());

        boolean hasMore = replyPage.hasNext();
        String nextCursor = hasMore ? "page_" + (page + 1) : null;

        MessageResponse rootResponse = enrichMessage(root);
        return new ThreadResponse(
                rootResponse,
                messages,
                (int) replyPage.getTotalElements(),
                nextCursor,
                hasMore
        );
    }

    /**
     * アクティブスレッド一覧を取得する（reply_count > 0 のトップレベルメッセージ）。
     *
     * @param channelId チャンネルID
     * @param cursor    カーソル（ページネーション用）
     * @param limit     取得件数
     * @return アクティブスレッドアイテムレスポンスのカーソルページ
     */
    public CursorPagedResponse<ActiveThreadItemResponse> getActiveThreads(
            Long channelId, String cursor, Integer limit) {
        int effectiveLimit = resolveLimit(limit);
        int page = parseCursorAsPage(cursor);
        Pageable pageable = PageRequest.of(page, effectiveLimit + 1);

        Page<ChatMessageEntity> threadPage = messageRepository.findActiveThreadsByChannelId(channelId, pageable);
        List<ChatMessageEntity> threads = threadPage.getContent();

        boolean hasNext = threads.size() > effectiveLimit;
        List<ChatMessageEntity> pageItems = hasNext ? threads.subList(0, effectiveLimit) : threads;

        List<ActiveThreadItemResponse> responses = pageItems.stream()
                .map(m -> new ActiveThreadItemResponse(
                        m.getId(),
                        m.getSenderId(),
                        null, // senderDisplayName は UserQueryService 経由で取得（現在は null）
                        m.getBody(),
                        m.getReplyCount(),
                        m.getUpdatedAt(),
                        truncate(m.getBody(), PREVIEW_LENGTH),
                        m.getCreatedAt()
                ))
                .collect(Collectors.toList());

        String nextCursor = hasNext ? "page_" + (page + 1) : null;
        return CursorPagedResponse.of(
                responses,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, effectiveLimit)
        );
    }

    /**
     * カーソル文字列をページ番号に変換する。
     *
     * @param cursor カーソル文字列（"page_N" 形式）。null の場合は 0 を返す
     * @return ページ番号
     */
    private int parseCursorAsPage(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor.replace("page_", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 文字列を指定長で切り詰める。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * メッセージをピン留め/解除する。
     *
     * @param messageId メッセージID
     * @param pinned    ピン留めするかどうか
     * @return 更新されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse togglePin(Long messageId, boolean pinned) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        if (pinned) {
            message.pin();
        } else {
            message.unpin();
        }
        ChatMessageEntity saved = messageRepository.save(message);
        log.info("メッセージピン留め変更: messageId={}, pinned={}", messageId, pinned);
        return enrichMessage(saved);
    }

    /**
     * メッセージを転送する。
     *
     * @param messageId メッセージID
     * @param request   転送リクエスト
     * @param userId    転送者ユーザーID
     * @return 転送されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse forwardMessage(Long messageId, ForwardMessageRequest request, Long userId) {
        ChatMessageEntity original = findMessageOrThrow(messageId);
        channelService.findChannelOrThrow(request.getTargetChannelId());

        String body = request.getAdditionalComment() != null
                ? request.getAdditionalComment() + "\n\n" + original.getBody()
                : original.getBody();

        ChatMessageEntity forwarded = ChatMessageEntity.builder()
                .channelId(request.getTargetChannelId())
                .senderId(userId)
                .body(body)
                .forwardedFromId(messageId)
                .build();

        ChatMessageEntity saved = messageRepository.save(forwarded);
        log.info("メッセージ転送完了: originalId={}, forwardedId={}, targetChannelId={}",
                messageId, saved.getId(), request.getTargetChannelId());
        return enrichMessage(saved);
    }

    /**
     * メッセージを検索する。
     *
     * @param channelId チャンネルID
     * @param keyword   検索キーワード
     * @param limit     取得件数
     * @return メッセージレスポンスリスト
     */
    public List<MessageResponse> searchMessages(Long channelId, String keyword, Integer limit) {
        int effectiveLimit = resolveLimit(limit);
        Pageable pageable = PageRequest.of(0, effectiveLimit);
        List<ChatMessageEntity> messages = messageRepository.searchByKeyword(channelId, keyword, pageable);
        return enrichMessages(messages);
    }

    /**
     * メッセージエンティティを取得する。見つからない場合は例外をスローする。
     *
     * @param messageId メッセージID
     * @return メッセージエンティティ
     */
    ChatMessageEntity findMessageOrThrow(Long messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));
    }

    private List<AttachmentResponse> saveAttachments(Long messageId,
                                                     List<AttachmentRequest> attachments,
                                                     ChatChannelEntity channel,
                                                     Long senderId) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<AttachmentResponse> responses = new ArrayList<>();
        for (AttachmentRequest req : attachments) {
            ChatMessageAttachmentEntity attachment = ChatMessageAttachmentEntity.builder()
                    .messageId(messageId)
                    .fileKey(req.getFileKey())
                    .fileName(req.getFileName())
                    .fileSize(req.getFileSize())
                    .contentType(req.getContentType())
                    .build();
            ChatMessageAttachmentEntity saved = attachmentRepository.save(attachment);

            // F13 Phase 4-β: 添付 INSERT 直後に統合クォータ使用量を加算
            chatAttachmentService.recordAttachmentUpload(channel, saved, senderId);

            responses.add(chatMapper.toAttachmentResponse(saved));
        }
        return responses;
    }

    private MessageResponse enrichMessage(ChatMessageEntity message) {
        List<ChatMessageAttachmentEntity> attachments = attachmentRepository.findByMessageId(message.getId());
        List<ChatMessageReactionEntity> reactions = reactionRepository.findByMessageId(message.getId());
        return chatMapper.toMessageResponseWithDetails(
                message,
                chatMapper.toAttachmentResponseList(attachments),
                chatMapper.toReactionResponseList(reactions)
        );
    }

    private List<MessageResponse> enrichMessages(List<ChatMessageEntity> messages) {
        List<MessageResponse> responses = new ArrayList<>();
        for (ChatMessageEntity message : messages) {
            responses.add(enrichMessage(message));
        }
        return responses;
    }

    private void validateMessageOwner(ChatMessageEntity message, Long userId) {
        if (!userId.equals(message.getSenderId())) {
            throw new BusinessException(ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_MESSAGE_LIMIT;
        }
        return Math.min(limit, MAX_MESSAGE_LIMIT);
    }
}
