package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.entity.PointRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ポイントルールリポジトリ。
 */
public interface PointRuleRepository extends JpaRepository<PointRuleEntity, Long> {

    /**
     * スコープのアクティブなルール一覧を取得する。
     */
    List<PointRuleEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);

    /**
     * スコープとアクション種別でアクティブなルールを検索する。
     */
    Optional<PointRuleEntity> findByScopeTypeAndScopeIdAndActionTypeAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId, ActionType actionType);
}
