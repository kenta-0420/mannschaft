package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ゲーミフィケーション設定リポジトリ。
 */
public interface GamificationConfigRepository extends JpaRepository<GamificationConfigEntity, Long> {

    /**
     * スコープで設定を検索する。
     */
    Optional<GamificationConfigEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
