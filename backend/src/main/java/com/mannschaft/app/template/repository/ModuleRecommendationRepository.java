package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.ModuleRecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * モジュール推奨関係リポジトリ。
 */
public interface ModuleRecommendationRepository extends JpaRepository<ModuleRecommendationEntity, Long> {

    List<ModuleRecommendationEntity> findByModuleId(Long moduleId);
}
