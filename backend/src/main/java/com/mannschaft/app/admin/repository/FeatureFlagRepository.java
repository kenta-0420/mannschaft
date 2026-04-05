package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * フィーチャーフラグリポジトリ。
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlagEntity, Long> {

    /**
     * フラグキーでフィーチャーフラグを取得する。
     */
    Optional<FeatureFlagEntity> findByFlagKey(String flagKey);

    /**
     * フラグキーの存在チェック。
     */
    boolean existsByFlagKey(String flagKey);
}
