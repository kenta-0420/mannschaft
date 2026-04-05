package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddReactionRequest;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チャットリアクションサービス。メッセージへの絵文字リアクションの追加・削除を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatReactionService {

    private final ChatMessageReactionRepository reactionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageService messageService;
    private final ChatMapper chatMapper;

    /**
     * リアクションを追加する。
     *
     * @param messageId メッセージID
     * @param request   リアクション追加リクエスト
     * @param userId    ユーザーID
     * @return リアクションレスポンス
     */
    @Transactional
    public ReactionResponse addReaction(Long messageId, AddReactionRequest request, Long userId) {
        ChatMessageEntity message = messageService.findMessageOrThrow(messageId);

        if (reactionRepository.existsByMessageIdAndUserIdAndEmoji(messageId, userId, request.getEmoji())) {
            throw new BusinessException(ChatErrorCode.REACTION_ALREADY_EXISTS);
        }

        ChatMessageReactionEntity reaction = ChatMessageReactionEntity.builder()
                .messageId(messageId)
                .userId(userId)
                .emoji(request.getEmoji())
                .build();

        ChatMessageReactionEntity saved = reactionRepository.save(reaction);

        message.incrementReactionCount();
        messageRepository.save(message);

        log.info("リアクション追加完了: messageId={}, emoji={}, userId={}", messageId, request.getEmoji(), userId);
        return chatMapper.toReactionResponse(saved);
    }

    /**
     * リアクションを削除する。
     *
     * @param messageId メッセージID
     * @param emoji     絵文字
     * @param userId    ユーザーID
     */
    @Transactional
    public void removeReaction(Long messageId, String emoji, Long userId) {
        ChatMessageEntity message = messageService.findMessageOrThrow(messageId);

        if (!reactionRepository.existsByMessageIdAndUserIdAndEmoji(messageId, userId, emoji)) {
            throw new BusinessException(ChatErrorCode.REACTION_NOT_FOUND);
        }

        reactionRepository.deleteByMessageIdAndUserIdAndEmoji(messageId, userId, emoji);

        message.decrementReactionCount();
        messageRepository.save(message);

        log.info("リアクション削除完了: messageId={}, emoji={}, userId={}", messageId, emoji, userId);
    }

    /**
     * メッセージのリアクション一覧を取得する。
     *
     * @param messageId メッセージID
     * @return リアクションレスポンスリスト
     */
    public List<ReactionResponse> listReactions(Long messageId) {
        messageService.findMessageOrThrow(messageId);
        List<ChatMessageReactionEntity> reactions = reactionRepository.findByMessageId(messageId);
        return chatMapper.toReactionResponseList(reactions);
    }
}
