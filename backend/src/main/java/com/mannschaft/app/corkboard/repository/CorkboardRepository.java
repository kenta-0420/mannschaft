package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * コルクボードリポジトリ。
 */
public interface CorkboardRepository extends JpaRepository<CorkboardEntity, Long> {

    /**
     * 個人ボード一覧を取得する。
     */
    List<CorkboardEntity> findByOwnerIdAndScopeTypeOrderByCreatedAtDesc(Long ownerId, String scopeType);

    /**
     * チーム/組織ボード一覧を取得する。
     */
    List<CorkboardEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(String scopeType, Long scopeId);

    /**
     * ボードIDとオーナーIDで取得する（個人用）。
     */
    Optional<CorkboardEntity> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * ボードIDとスコープで取得する（チーム/組織用）。
     */
    Optional<CorkboardEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    /**
     * オーナーのボード数を取得する。
     */
    long countByOwnerId(Long ownerId);

    /**
     * スコープ別のボード数を取得する。
     */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
