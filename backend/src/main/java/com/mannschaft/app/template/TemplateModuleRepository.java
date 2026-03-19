package com.mannschaft.app.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * テンプレート×モジュール紐付けリポジトリ。
 */
public interface TemplateModuleRepository extends JpaRepository<TemplateModuleEntity, Long> {

    List<TemplateModuleEntity> findByTemplateId(Long templateId);
}
