package com.mannschaft.app.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * モジュール定義リポジトリ。
 */
public interface ModuleDefinitionRepository extends JpaRepository<ModuleDefinitionEntity, Long> {

    List<ModuleDefinitionEntity> findByModuleType(ModuleDefinitionEntity.ModuleType moduleType);

    Optional<ModuleDefinitionEntity> findBySlug(String slug);

    List<ModuleDefinitionEntity> findByIsActiveTrue();
}
