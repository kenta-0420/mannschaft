package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.GamificationUserSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ゲーミフィケーションユーザー設定リポジトリ。
 */
public interface GamificationUserSettingRepository extends JpaRepository<GamificationUserSettingEntity, Long> {

    /**
     * ユーザーIDとスコープで設定を検索する。
     */
    Optional<GamificationUserSettingEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);
}
