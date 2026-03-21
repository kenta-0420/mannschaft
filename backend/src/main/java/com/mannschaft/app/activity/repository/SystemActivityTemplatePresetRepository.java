package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.PresetCategory;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * プラットフォーム標準テンプレートプリセットリポジトリ。
 */
public interface SystemActivityTemplatePresetRepository extends JpaRepository<SystemActivityTemplatePresetEntity, Long> {

    List<SystemActivityTemplatePresetEntity> findByCategoryAndIsActiveTrueOrderByNameAsc(PresetCategory category);

    List<SystemActivityTemplatePresetEntity> findByIsActiveTrueOrderByCategoryAscNameAsc();
}
