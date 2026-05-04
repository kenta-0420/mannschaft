package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 役職カタログ Repository。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.3</p>
 */
public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    /**
     * 指定スコープに対する有効な（deleted_at IS NULL）役職カタログを sort_order 昇順で取得する。
     */
    @Query("SELECT p FROM PositionEntity p " +
            "WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId " +
            "AND p.deletedAt IS NULL " +
            "ORDER BY p.sortOrder ASC, p.name ASC")
    List<PositionEntity> findActiveByScope(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定スコープ + name から役職カタログを取得する（uq_positions_scope_name 索引活用）。
     */
    @Query("SELECT p FROM PositionEntity p " +
            "WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId AND p.name = :name")
    Optional<PositionEntity> findByScopeAndName(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("name") String name);
}
