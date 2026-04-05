package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.WorkflowStatus;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー申請リポジトリ。
 */
public interface WorkflowRequestRepository extends JpaRepository<WorkflowRequestEntity, Long> {

    /**
     * スコープ内の申請一覧をページング取得する。
     */
    Page<WorkflowRequestEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ内の申請一覧をステータスでフィルタしてページング取得する。
     */
    Page<WorkflowRequestEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, WorkflowStatus status, Pageable pageable);

    /**
     * ユーザーの申請一覧を取得する。
     */
    List<WorkflowRequestEntity> findByRequestedByOrderByCreatedAtDesc(Long requestedBy);

    /**
     * IDとスコープで申請を取得する。
     */
    Optional<WorkflowRequestEntity> findByIdAndScopeTypeAndScopeId(
            Long id, String scopeType, Long scopeId);

    /**
     * スコープ内のステータス別件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, WorkflowStatus status);

    /**
     * ソースタイプとソースIDで申請を取得する。
     */
    Optional<WorkflowRequestEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
}
