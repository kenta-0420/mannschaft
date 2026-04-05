package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー申請コメントリポジトリ。
 */
public interface WorkflowRequestCommentRepository extends JpaRepository<WorkflowRequestCommentEntity, Long> {

    /**
     * 申請IDでコメント一覧を作成日時順に取得する。
     */
    List<WorkflowRequestCommentEntity> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    /**
     * IDと申請IDでコメントを取得する。
     */
    Optional<WorkflowRequestCommentEntity> findByIdAndRequestId(Long id, Long requestId);
}
