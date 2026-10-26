package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフローテンプレートリポジトリ。
 */
public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplateEntity, Long> {

    /**
     * スコープ内のテンプレート一覧をページング取得する。
     */
    Page<WorkflowTemplateEntity> findByScopeTypeAndScopeIdOrderBySortOrderAsc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ内の有効なテンプレート一覧を取得する。
     */
    List<WorkflowTemplateEntity> findByScopeTypeAndScopeIdAndIsActiveTrueOrderBySortOrderAsc(
            String scopeType, Long scopeId);

    /**
     * IDとスコープでテンプレートを取得する。
     */
    Optional<WorkflowTemplateEntity> findByIdAndScopeTypeAndScopeId(
            Long id, String scopeType, Long scopeId);
}
