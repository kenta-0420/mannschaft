package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.TemplateModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * テンプレート×モジュール紐付けリポジトリ。
 */
public interface TemplateModuleRepository extends JpaRepository<TemplateModuleEntity, Long> {

    List<TemplateModuleEntity> findByTemplateId(Long templateId);
}
