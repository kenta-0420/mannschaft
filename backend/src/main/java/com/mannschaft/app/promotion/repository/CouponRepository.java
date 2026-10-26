package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.CouponEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * クーポンリポジトリ。
 */
public interface CouponRepository extends JpaRepository<CouponEntity, Long> {

    @Query("""
            SELECT c FROM CouponEntity c
            WHERE c.scopeType = :scopeType AND c.scopeId = :scopeId
            ORDER BY c.createdAt DESC
            """)
    Page<CouponEntity> findByScopeTypeAndScopeId(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    @Query("SELECT c FROM CouponEntity c WHERE c.id = :id AND c.scopeType = :scopeType AND c.scopeId = :scopeId")
    Optional<CouponEntity> findByIdAndScope(
            @Param("id") Long id,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
