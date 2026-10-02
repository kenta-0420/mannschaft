package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Google カレンダー Webhook チャンネルリポジトリ。
 */
public interface GoogleCalendarWebhookChannelRepository extends JpaRepository<GoogleCalendarWebhookChannelEntity, UUID> {

    /**
     * ユーザーIDでチャンネルを取得する。
     */
    Optional<GoogleCalendarWebhookChannelEntity> findByUserId(Long userId);

    /**
     * チャンネルIDでチャンネルを取得する（Webhook 受信時の検索用）。
     */
    Optional<GoogleCalendarWebhookChannelEntity> findByChannelId(String channelId);

    /**
     * 有効期限が指定日時以前のチャンネル一覧を取得する（バッチ更新用）。
     */
    List<GoogleCalendarWebhookChannelEntity> findByExpiresAtLessThanEqual(LocalDateTime threshold);
}
