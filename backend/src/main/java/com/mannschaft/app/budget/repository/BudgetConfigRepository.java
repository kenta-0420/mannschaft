package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 予算設定リポジトリ。
 */
public interface BudgetConfigRepository extends JpaRepository<BudgetConfigEntity, Long> {

    /**
     * スコープで設定を検索する。
     */
    Optional<BudgetConfigEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
