package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowTemplateFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ワークフローテンプレートフィールドリポジトリ。
 */
public interface WorkflowTemplateFieldRepository extends JpaRepository<WorkflowTemplateFieldEntity, Long> {

    /**
     * テンプレートIDでフィールド一覧をソート順に取得する。
     */
    List<WorkflowTemplateFieldEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    /**
     * テンプレートIDで全フィールドを削除する。
     */
    void deleteByTemplateId(Long templateId);
}
