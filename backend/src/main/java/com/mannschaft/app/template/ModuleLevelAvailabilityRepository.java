package com.mannschaft.app.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * モジュール×レベル別利用可否リポジトリ。
 */
public interface ModuleLevelAvailabilityRepository extends JpaRepository<ModuleLevelAvailabilityEntity, Long> {

    List<ModuleLevelAvailabilityEntity> findByModuleId(Long moduleId);

    Optional<ModuleLevelAvailabilityEntity> findByModuleIdAndLevel(Long moduleId, ModuleLevelAvailabilityEntity.Level level);
}
