package com.mannschaft.app.chat.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * チャット予約送信バッチ。
 *
 * <p>{@code scheduled_at} が現在時刻以前かつ未配信（{@code scheduled_sent_at IS NULL}）の
 * メッセージを1分おきに処理し、STOMP で全チャンネルメンバーにリアルタイム配信する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatScheduledMessageBatchService {

    /** 送信者の表示名が取得できない場合のフォールバック（ChatMessageService と同一規約）。 */
    private static final String DEFAULT_SENDER_DISPLAY_NAME = "ユーザー";

    private final ChatMessageRepository messageRepository;
    private final ChatMessagePublisher publisher;
    private final ChatMapper chatMapper;
    /**
     * 配信メッセージに送信者の表示名・アバターを付与するために使用。
     * auth ドメインの UserEntity/UserRepository を直接参照せず、common の {@link NameResolverService} に委譲する
     * （クロスドメイン・原則1）。
     */
    private final NameResolverService nameResolver;

    @BatchEndpoint(name = "chat-scheduled-message-dispatch", description = "予約送信チャットメッセージを 1 分毎に STOMP 配信する")
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void processScheduledMessages() {
        LocalDateTime now = LocalDateTime.now();
        List<ChatMessageEntity> pending = messageRepository.findPendingScheduledMessages(now);

        if (pending.isEmpty()) {
            return;
        }

        log.info("[ChatScheduledMessageBatch] 配信開始: 対象={}件", pending.size());

        // N+1 回避: 配信対象全件の senderId を集約し、表示名・アバターを各 1 クエリで一括解決
        Map<Long, MessageResponse.SenderDto> senders = resolveSenders(pending);

        int successCount = 0;
        int failCount = 0;

        for (ChatMessageEntity message : pending) {
            try {
                MessageResponse response = chatMapper.toMessageResponseWithDetails(
                        message, List.of(), List.of(), senderDtoOf(message.getSenderId(), senders));
                publisher.publishCreated(message.getChannelId(), response);

                message.markScheduledSent(now);
                messageRepository.save(message);

                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("[ChatScheduledMessageBatch] 配信失敗: messageId={}, channelId={}",
                        message.getId(), message.getChannelId(), e);
            }
        }

        log.info("[ChatScheduledMessageBatch] 完了: 成功={}件, 失敗={}件", successCount, failCount);
    }

    /**
     * 配信対象メッセージ群の送信者表示情報を一括解決する。
     * common の {@link NameResolverService} に委譲し、表示名・アバターをそれぞれ 1 クエリで取得する。
     */
    private Map<Long, MessageResponse.SenderDto> resolveSenders(List<ChatMessageEntity> messages) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ChatMessageEntity message : messages) {
            if (message.getSenderId() != null) {
                ids.add(message.getSenderId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> displayNames = nameResolver.resolveUserDisplayNames(ids);
        Map<Long, String> avatarUrls = nameResolver.resolveUserAvatarUrls(ids);
        Map<Long, MessageResponse.SenderDto> result = new java.util.HashMap<>();
        for (Long id : ids) {
            String displayName = displayNames.get(id);
            if (displayName == null) {
                displayName = DEFAULT_SENDER_DISPLAY_NAME;
            }
            result.put(id, new MessageResponse.SenderDto(id, displayName, avatarUrls.get(id)));
        }
        return result;
    }

    /**
     * 解決済み Map から senderId に対応する {@link MessageResponse.SenderDto} を引く。
     * senderId が null なら null、Map に無ければ "ユーザー" フォールバックの DTO を返す。
     */
    private MessageResponse.SenderDto senderDtoOf(Long senderId, Map<Long, MessageResponse.SenderDto> resolved) {
        if (senderId == null) {
            return null;
        }
        MessageResponse.SenderDto dto = resolved.get(senderId);
        return dto != null ? dto : new MessageResponse.SenderDto(senderId, DEFAULT_SENDER_DISPLAY_NAME, null);
    }
}
