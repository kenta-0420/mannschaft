package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.ApproverDecision;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー申請承認者リポジトリ。
 */
public interface WorkflowRequestApproverRepository extends JpaRepository<WorkflowRequestApproverEntity, Long> {

    /**
     * ステップIDで承認者一覧を取得する。
     */
    List<WorkflowRequestApproverEntity> findByRequestStepId(Long requestStepId);

    /**
     * ステップIDとユーザーIDで承認者を取得する。
     */
    Optional<WorkflowRequestApproverEntity> findByRequestStepIdAndApproverUserId(
            Long requestStepId, Long approverUserId);

    /**
     * ステップIDと判断で承認者数を取得する。
     */
    long countByRequestStepIdAndDecision(Long requestStepId, ApproverDecision decision);

    /**
     * ステップIDで承認者数を取得する。
     */
    long countByRequestStepId(Long requestStepId);
}
