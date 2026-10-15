package com.mannschaft.app.chat.event;

import lombok.Getter;

/**
 * F10.7 問い合わせチャンネル設定変更イベント。
 *
 * <p>{@link com.mannschaft.app.chat.service.ChatChannelService#updateInquiryChannel} で
 * {@code is_inquiry_channel} フラグが変更された際に発行される。</p>
 *
 * <p>{@link com.mannschaft.app.admin.service.AdminBusinessAlertService} がこのイベントを受信し、
 * 該当チームの ADMIN ユーザーの業務アラートキャッシュ（Valkey）を削除することで、
 * inquiry チャンネル設定変更がウィジェットに即時反映される。</p>
 */
@Getter
public class InquiryChannelChangedEvent {
    /** 変更されたチャンネルが属するチーム ID */
    private final Long teamId;
    /** 変更されたチャンネル ID */
    private final Long channelId;

    public InquiryChannelChangedEvent(Long teamId, Long channelId) {
        this.teamId = teamId;
        this.channelId = channelId;
    }
}
