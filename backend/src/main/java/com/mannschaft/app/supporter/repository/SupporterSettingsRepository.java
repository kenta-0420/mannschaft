package com.mannschaft.app.supporter.repository;

import com.mannschaft.app.supporter.entity.SupporterSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * サポーター設定リポジトリ。
 */
public interface SupporterSettingsRepository extends JpaRepository<SupporterSettingsEntity, Long> {

    Optional<SupporterSettingsEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
