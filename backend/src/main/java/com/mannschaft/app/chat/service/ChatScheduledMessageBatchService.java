package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>{@code fixedDelay = 60_000} ms（1分おき）</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>処理フロー</h2>
 * <ol>
 *   <li>{@code scheduled_at <= NOW() AND scheduled_sent_at IS NULL} のメッセージを取得</li>
 *   <li>各メッセージを {@code /topic/channels/{channelId}} へ STOMP ブロードキャスト</li>
 *   <li>{@code scheduled_sent_at} に現在時刻をセットして配信済みとしてマーク</li>
 * </ol>
 *
 * <h2>担当外の副作用について</h2>
 * <p>
 * 未読カウント更新・プッシュ通知・メンション解析などの副作用は
 * {@code ChatMessageService#sendMessage} 側（BE-A 担当）が将来統合する予定。
 * 本バッチは WebSocket 配信と配信済みマークのみを責務とする。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatScheduledMessageBatchService {

    private static final String TOPIC_FORMAT = "/topic/channels/%d";

    private final ChatMessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMapper chatMapper;

    /**
     * 1分おきに予約送信対象メッセージを配信する。
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "chatScheduledMessageBatch", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    @Transactional
    public void processScheduledMessages() {
        LocalDateTime now = LocalDateTime.now();
        List<ChatMessageEntity> pending = messageRepository.findPendingScheduledMessages(now);

        if (pending.isEmpty()) {
            return;
        }

        log.info("[ChatScheduledMessageBatch] 配信開始: 対象={}件, 基準時刻={}", pending.size(), now);

        int successCount = 0;
        int failCount = 0;

        for (ChatMessageEntity message : pending) {
            try {
                // WebSocket で MESSAGE_CREATED をブロードキャスト
                MessageResponse response = chatMapper.toMessageResponse(message);
                String destination = String.format(TOPIC_FORMAT, message.getChannelId());
                messagingTemplate.convertAndSend(destination, response);

                // 配信済みとしてマーク
                message.markScheduledSent(now);
                messageRepository.save(message);

                successCount++;
                log.debug("[ChatScheduledMessageBatch] 配信完了: messageId={}, channelId={}",
                        message.getId(), message.getChannelId());

            } catch (Exception e) {
                failCount++;
                log.error("[ChatScheduledMessageBatch] 配信失敗: messageId={}, channelId={}",
                        message.getId(), message.getChannelId(), e);
                // 1件の失敗で全体を止めない。次回のバッチで再試行する。
            }
        }

        log.info("[ChatScheduledMessageBatch] 完了: 成功={}件, 失敗={}件", successCount, failCount);
    }
}
