package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ワークフローテンプレートステップリポジトリ。
 */
public interface WorkflowTemplateStepRepository extends JpaRepository<WorkflowTemplateStepEntity, Long> {

    /**
     * テンプレートIDでステップ一覧をステップ順に取得する。
     */
    List<WorkflowTemplateStepEntity> findByTemplateIdOrderByStepOrderAsc(Long templateId);

    /**
     * テンプレートIDで全ステップを削除する。
     */
    void deleteByTemplateId(Long templateId);
}
