package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.dto.ChatChannelEventPayload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * F04.2.1 §3.10.1: チャンネル状態変化イベントを STOMP で配信する。
 * <p>
 * 送信先トピック: {@code /topic/channels/{channelId}/events}
 * </p>
 * <p>
 * フロントエンドはこのトピックを購読し、受信したイベント種別に応じて
 * タブの自動クローズ・無効化を行う（F04.2.1 §3.10）。
 * </p>
 */
@Service
public class ChatChannelEventPublisher {

    private static final String DESTINATION_FORMAT = "/topic/channels/%d/events";

    private final SimpMessagingTemplate messagingTemplate;

    public ChatChannelEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * メンバーがチャンネルから kick されたことを通知する。
     *
     * @param channelId    対象チャンネルID
     * @param kickedUserId kick されたユーザーID
     */
    public void publishMemberKicked(Long channelId, Long kickedUserId) {
        send(channelId, ChatChannelEventPayload.memberKicked(kickedUserId));
    }

    /**
     * チャンネルが削除されたことを通知する。
     *
     * @param channelId 対象チャンネルID
     */
    public void publishChannelDeleted(Long channelId) {
        send(channelId, ChatChannelEventPayload.channelDeleted());
    }

    /**
     * チャンネルがアーカイブされたことを通知する。
     *
     * @param channelId 対象チャンネルID
     */
    public void publishChannelArchived(Long channelId) {
        send(channelId, ChatChannelEventPayload.channelArchived());
    }

    /**
     * チャンネルのアーカイブが解除されたことを通知する。
     *
     * @param channelId 対象チャンネルID
     */
    public void publishChannelUnarchived(Long channelId) {
        send(channelId, ChatChannelEventPayload.channelUnarchived());
    }

    private void send(Long channelId, ChatChannelEventPayload payload) {
        messagingTemplate.convertAndSend(String.format(DESTINATION_FORMAT, channelId), payload);
    }
}
