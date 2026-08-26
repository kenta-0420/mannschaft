package com.mannschaft.app.chat.event;

import lombok.Getter;

/**
 * F10.7 問い合わせチャンネルへのメッセージ受信イベント。
 *
 * <p>問い合わせチャンネル（{@code is_inquiry_channel=true}）へ一般ユーザーが
 * メッセージを送信した場合に {@link com.mannschaft.app.chat.service.ChatMessageService} が発行する。
 * {@link InquiryChatEventListener} が受信し、チームの ADMIN / DEPUTY_ADMIN へ通知を送信する。</p>
 */
@Getter
public class InquiryReceivedEvent {
    private final Long teamId;
    private final Long channelId;
    private final String channelName;
    private final Long actorUserId;
    private final String senderDisplayName;
    /**
     * 受信した問い合わせメッセージの ID（F00 CHAT_MESSAGE 可視性判定の sourceId 用）。
     *
     * <p>通知の {@code sourceType="CHAT_MESSAGE"} に対応する実体はメッセージ ID である。
     * 従来はチャンネル ID を sourceId に渡していたため
     * {@link com.mannschaft.app.chat.visibility.ChatMessageVisibilityResolver} が対象を解決できず、
     * 通知が visibility deny で作成されなかった。本フィールドで正しいメッセージ ID を伝搬する。</p>
     */
    private final Long messageId;

    public InquiryReceivedEvent(Long teamId, Long channelId, String channelName,
                                Long actorUserId, String senderDisplayName, Long messageId) {
        this.teamId = teamId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.actorUserId = actorUserId;
        this.senderDisplayName = senderDisplayName;
        this.messageId = messageId;
    }
}
