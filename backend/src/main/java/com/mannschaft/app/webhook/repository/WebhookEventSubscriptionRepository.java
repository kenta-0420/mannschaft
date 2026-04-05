package com.mannschaft.app.webhook.repository;

import com.mannschaft.app.webhook.entity.WebhookEventSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Webhookイベントサブスクリプションリポジトリ。
 */
public interface WebhookEventSubscriptionRepository extends JpaRepository<WebhookEventSubscriptionEntity, Long> {

    /**
     * エンドポイントIDに紐づくサブスクリプション一覧を返す。
     *
     * @param endpointId エンドポイントID
     * @return サブスクリプションリスト
     */
    List<WebhookEventSubscriptionEntity> findByEndpointId(Long endpointId);

    /**
     * 指定イベント種別をサブスクライブしているサブスクリプション一覧を返す（配信対象エンドポイント検索用）。
     *
     * @param eventType イベント種別
     * @return サブスクリプションリスト
     */
    List<WebhookEventSubscriptionEntity> findByEventType(String eventType);
}
