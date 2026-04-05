package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.PromotionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * プロモーションリポジトリ。
 */
public interface PromotionRepository extends JpaRepository<PromotionEntity, Long> {

    @Query("""
            SELECT p FROM PromotionEntity p
            WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId
              AND (:status IS NULL OR p.status = :status)
            ORDER BY p.createdAt DESC
            """)
    Page<PromotionEntity> findByScopeTypeAndScopeId(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("status") String status,
            Pageable pageable);

    @Query("SELECT p FROM PromotionEntity p WHERE p.id = :id AND p.scopeType = :scopeType AND p.scopeId = :scopeId")
    Optional<PromotionEntity> findByIdAndScope(
            @Param("id") Long id,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
