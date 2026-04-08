package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AttachmentRequest;
import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.notification.service.MentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final MentionService mentionService;

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

        ChatMessageEntity message = ChatMessageEntity.builder()
                .channelId(channelId)
                .senderId(senderId)
                .parentId(request.getParentId())
                .body(request.getBody())
                .scheduledAt(request.getScheduledAt())
                .build();

        ChatMessageEntity saved = messageRepository.save(message);

        // 親メッセージの返信数をインクリメント
        if (request.getParentId() != null) {
            ChatMessageEntity parent = findMessageOrThrow(request.getParentId());
            parent.incrementReplyCount();
            messageRepository.save(parent);
        }

        // 添付ファイルを保存
        List<AttachmentResponse> attachmentResponses = saveAttachments(saved.getId(), request.getAttachments());

        // チャンネルの最終メッセージ情報を更新
        String preview = request.getBody().length() > PREVIEW_LENGTH
                ? request.getBody().substring(0, PREVIEW_LENGTH)
                : request.getBody();
        channel.updateLastMessage(LocalDateTime.now(), preview);

        // チャンネルメンバーにリアルタイム通知（送信者自身を除く）
        // NOTE: チャンネルメンバー一覧取得はChannelMemberRepository連携後に拡張
        // 現時点ではNotificationHelperで通知レコード作成+WebSocket配信
        // 未読カウントのインクリメントはNotificationService側で管理

        // 本文中の @contactHandle からメンションレコードを作成
        if (request.getBody() != null && !request.getBody().isBlank()) {
            mentionService.createMentionsFromText(
                    senderId,
                    "MESSAGE",
                    saved.getId(),
                    null,
                    request.getBody(),
                    "/chat/" + channelId + "?messageId=" + saved.getId());
        }

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

        message.softDelete();
        messageRepository.save(message);
        log.info("メッセージ削除完了: messageId={}", messageId);
    }

    /**
     * スレッド返信一覧を取得する。
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

    private List<AttachmentResponse> saveAttachments(Long messageId, List<AttachmentRequest> attachments) {
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
