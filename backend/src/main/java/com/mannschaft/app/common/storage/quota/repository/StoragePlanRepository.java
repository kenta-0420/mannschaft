package com.mannschaft.app.common.storage.quota.repository;

import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F13 ストレージプランのリポジトリ。
 */
public interface StoragePlanRepository extends JpaRepository<StoragePlanEntity, Long> {

    /**
     * 指定 scope_level のデフォルトプランを取得する（{@code is_default = TRUE} かつ未削除）。
     */
    Optional<StoragePlanEntity> findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(String scopeLevel);
}
