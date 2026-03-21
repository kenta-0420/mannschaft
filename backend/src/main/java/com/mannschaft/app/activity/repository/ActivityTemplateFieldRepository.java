package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 活動テンプレートフィールド定義リポジトリ。
 */
public interface ActivityTemplateFieldRepository extends JpaRepository<ActivityTemplateFieldEntity, Long> {

    List<ActivityTemplateFieldEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    void deleteByTemplateId(Long templateId);

    long countByTemplateId(Long templateId);
}
