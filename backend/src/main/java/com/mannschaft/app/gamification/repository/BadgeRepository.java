package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.BadgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * バッジリポジトリ。
 */
public interface BadgeRepository extends JpaRepository<BadgeEntity, Long> {

    /**
     * スコープのアクティブなバッジ一覧を取得する。
     */
    List<BadgeEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);
}
