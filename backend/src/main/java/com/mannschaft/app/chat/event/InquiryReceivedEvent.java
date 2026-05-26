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

    public InquiryReceivedEvent(Long teamId, Long channelId, String channelName,
                                Long actorUserId, String senderDisplayName) {
        this.teamId = teamId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.actorUserId = actorUserId;
        this.senderDisplayName = senderDisplayName;
    }
}
