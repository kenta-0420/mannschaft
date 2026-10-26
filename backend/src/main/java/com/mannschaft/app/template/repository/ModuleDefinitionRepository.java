package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
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

    /**
     * 全モジュールを moduleNumber 昇順で取得する（SYSTEM_ADMIN 管理画面用）。
     * DEFAULT/OPTIONAL・is_active の true/false を問わず全件返す
     * （{@code @SQLRestriction("deleted_at IS NULL")} により論理削除のみ自動除外）。
     */
    List<ModuleDefinitionEntity> findAllByOrderByModuleNumberAsc();
}
