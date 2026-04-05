package com.mannschaft.app.webhook.repository;

import com.mannschaft.app.webhook.entity.IncomingWebhookTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 受信WebhookトークンリポジトリRepository。
 */
public interface IncomingWebhookTokenRepository extends JpaRepository<IncomingWebhookTokenEntity, Long> {

    /**
     * トークン文字列でアクティブかつ有効なトークンを取得する。
     *
     * @param token トークン文字列
     * @return トークンEntity
     */
    Optional<IncomingWebhookTokenEntity> findByTokenAndIsActiveTrueAndDeletedAtIsNull(String token);

    /**
     * スコープに紐づく（削除済みを除く）トークン数を返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 件数
     */
    int countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);
}
