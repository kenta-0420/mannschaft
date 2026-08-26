package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.BadgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * バッジリポジトリ。
 */
public interface BadgeRepository extends JpaRepository<BadgeEntity, Long> {

    /**
     * スコープのアクティブなバッジ一覧を取得する。
     */
    List<BadgeEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);

    /**
     * スコープ × 名称で system badge を 1 件取得する（F20.3 ベータテスター称号の引き当て・設計書 01 §5）。
     *
     * <p>ベータテスター称号は sentinel スコープ（{@code scope_type='PLATFORM'}・{@code scope_id=0}）に
     * 名称 {@code 'ベータテスター'} で 1 行シードされる（{@code V162...seed_beta_tester_badge.sql}）。</p>
     */
    Optional<BadgeEntity> findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
            String scopeType, Long scopeId, String name);
}
