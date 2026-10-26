package com.mannschaft.app.webhook.repository;

import com.mannschaft.app.webhook.entity.WebhookDeliveryLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Webhook配信ログリポジトリ。
 */
public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLogEntity, Long> {

    /**
     * エンドポイントIDに紐づく配信ログを作成日時降順で返す。
     *
     * @param endpointId エンドポイントID
     * @return 配信ログリスト
     */
    List<WebhookDeliveryLogEntity> findByEndpointIdOrderByCreatedAtDesc(Long endpointId);
}
