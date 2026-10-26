package com.mannschaft.app.workflow.repository;

import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー申請添付ファイルリポジトリ。
 */
public interface WorkflowRequestAttachmentRepository extends JpaRepository<WorkflowRequestAttachmentEntity, Long> {

    /**
     * 申請IDで添付ファイル一覧を取得する。
     */
    List<WorkflowRequestAttachmentEntity> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    /**
     * IDと申請IDで添付ファイルを取得する。
     */
    Optional<WorkflowRequestAttachmentEntity> findByIdAndRequestId(Long id, Long requestId);
}
