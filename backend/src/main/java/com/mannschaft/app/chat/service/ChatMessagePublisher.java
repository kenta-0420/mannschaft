package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.dto.ChatMessageBroadcast;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.ReactionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * F04.2: WebSocket STOMP でチャットメッセージイベントを配信する。
 * <p>
 * 配信先トピック: {@code /topic/channels/{channelId}}
 * </p>
 * <p>
 * ペイロード形式: {@code { "type": "...", "data": { ... } }}
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessagePublisher {

    private static final String DESTINATION_FORMAT = "/topic/channels/%d";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * メッセージ送信イベントを配信する。
     *
     * @param channelId チャンネルID
     * @param msg       送信されたメッセージのレスポンス
     */
    public void publishCreated(Long channelId, MessageResponse msg) {
        send(channelId, ChatMessageBroadcast.messageCreated(msg));
        log.debug("MESSAGE_CREATED 配信: channelId={}, messageId={}", channelId, msg.getId());
    }

    /**
     * メッセージ編集イベントを配信する。
     *
     * @param channelId チャンネルID
     * @param msg       編集されたメッセージのレスポンス
     */
    public void publishUpdated(Long channelId, MessageResponse msg) {
        send(channelId, ChatMessageBroadcast.messageUpdated(msg));
        log.debug("MESSAGE_UPDATED 配信: channelId={}, messageId={}", channelId, msg.getId());
    }

    /**
     * メッセージ削除イベントを配信する。
     *
     * @param channelId チャンネルID
     * @param messageId 削除されたメッセージID
     * @param deletedAt 削除日時（ISO 8601 文字列）
     */
    public void publishDeleted(Long channelId, Long messageId, String deletedAt) {
        Map<String, Object> data = Map.of("id", messageId, "deleted_at", deletedAt);
        send(channelId, ChatMessageBroadcast.messageDeleted(data));
        log.debug("MESSAGE_DELETED 配信: channelId={}, messageId={}", channelId, messageId);
    }

    /**
     * リアクション更新イベントを配信する。
     *
     * @param channelId チャンネルID
     * @param messageId 対象メッセージID
     * @param reactions 更新後のリアクション一覧
     */
    public void publishReactionUpdated(Long channelId, Long messageId, List<ReactionResponse> reactions) {
        Map<String, Object> data = Map.of("messageId", messageId, "reactions", reactions);
        send(channelId, ChatMessageBroadcast.reactionUpdated(data));
        log.debug("REACTION_UPDATED 配信: channelId={}, messageId={}", channelId, messageId);
    }

    private void send(Long channelId, ChatMessageBroadcast payload) {
        messagingTemplate.convertAndSend(String.format(DESTINATION_FORMAT, channelId), payload);
    }
}
