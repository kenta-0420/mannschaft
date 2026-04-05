package com.mannschaft.app.line.repository;

import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * LINE BOT設定リポジトリ。
 */
public interface LineBotConfigRepository extends JpaRepository<LineBotConfigEntity, Long> {

    /**
     * スコープ種別とスコープIDで設定を取得する。
     */
    Optional<LineBotConfigEntity> findByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);

    /**
     * スコープ種別とスコープIDで設定が存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);

    /**
     * Webhookシークレットで設定を取得する。
     */
    Optional<LineBotConfigEntity> findByWebhookSecret(String webhookSecret);

    /**
     * チャンネルIDで設定を取得する。
     */
    Optional<LineBotConfigEntity> findByChannelId(String channelId);
}
