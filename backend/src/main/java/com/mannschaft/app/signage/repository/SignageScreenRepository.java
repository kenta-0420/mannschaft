package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageScreenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * デジタルサイネージ 画面リポジトリ。
 */
public interface SignageScreenRepository extends JpaRepository<SignageScreenEntity, Long> {

    /**
     * スコープに紐づくアクティブな画面一覧（未削除）を取得する。
     */
    List<SignageScreenEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * IDで画面を取得する（未削除）。
     */
    Optional<SignageScreenEntity> findByIdAndDeletedAtIsNull(Long id);
}
