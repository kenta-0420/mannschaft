package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.PointTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ポイントトランザクションリポジトリ。
 */
public interface PointTransactionRepository extends JpaRepository<PointTransactionEntity, Long> {

    /**
     * 二重付与防止のため、同一参照元のトランザクションを検索する。
     */
    Optional<PointTransactionEntity> findByUserIdAndScopeTypeAndScopeIdAndReferenceTypeAndReferenceId(
            Long userId, String scopeType, Long scopeId, String referenceType, Long referenceId);
}
