package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー申請ステップリポジトリ。
 */
public interface WorkflowRequestStepRepository extends JpaRepository<WorkflowRequestStepEntity, Long> {

    /**
     * 申請IDでステップ一覧をステップ順に取得する。
     */
    List<WorkflowRequestStepEntity> findByRequestIdOrderByStepOrderAsc(Long requestId);

    /**
     * 申請IDとステップ順序でステップを取得する。
     */
    Optional<WorkflowRequestStepEntity> findByRequestIdAndStepOrder(Long requestId, Integer stepOrder);
}
