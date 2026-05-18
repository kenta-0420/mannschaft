package com.mannschaft.app.chat.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    private final ChatMessageRepository messageRepository;
    private final ChatMessagePublisher publisher;
    private final ChatMapper chatMapper;

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

        int successCount = 0;
        int failCount = 0;

        for (ChatMessageEntity message : pending) {
            try {
                MessageResponse response = chatMapper.toMessageResponse(message);
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
}
