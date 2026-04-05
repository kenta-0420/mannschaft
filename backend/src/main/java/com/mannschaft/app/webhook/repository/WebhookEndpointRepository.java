package com.mannschaft.app.webhook.repository;

import com.mannschaft.app.webhook.entity.WebhookEndpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Webhookエンドポイントリポジトリ。
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, Long> {

    /**
     * スコープに紐づくアクティブなエンドポイント一覧を返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return エンドポイントリスト
     */
    List<WebhookEndpointEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);

    /**
     * スコープに紐づく（削除済みを除く）エンドポイント数を返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 件数
     */
    int countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);
}
