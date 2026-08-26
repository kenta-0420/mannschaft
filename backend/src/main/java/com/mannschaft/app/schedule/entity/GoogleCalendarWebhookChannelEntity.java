package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Google カレンダー Webhook チャンネル管理エンティティ。
 *
 * <p>Google Calendar API の push 通知チャンネル（watch リソース）を管理する。
 * ユーザーごとに 1 件のチャンネルを保持し、有効期限前に更新する運用を想定する。</p>
 */
@Entity
@Table(name = "google_calendar_webhook_channels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class GoogleCalendarWebhookChannelEntity extends UuidV7Entity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false, length = 255)
    private String channelId;

    @Column(name = "resource_id", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "channel_token", nullable = false, length = 64)
    private String channelToken;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_received_at")
    private LocalDateTime lastReceivedAt;

    /**
     * チャンネル情報を新しい watch レスポンスで更新する（再登録時に使用）。
     */
    public void updateChannel(String channelId, String resourceId, String channelToken, LocalDateTime expiresAt) {
        this.channelId = channelId;
        this.resourceId = resourceId;
        this.channelToken = channelToken;
        this.expiresAt = expiresAt;
    }

    /**
     * 最終受信日時を更新する（Webhook 受信時に使用）。
     */
    public void updateLastReceivedAt(LocalDateTime lastReceivedAt) {
        this.lastReceivedAt = lastReceivedAt;
    }
}
