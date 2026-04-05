package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.dto.WorkflowCommentRequest;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ワークフローコメントサービス。申請に対するコメントのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowCommentService {

    private final WorkflowRequestCommentRepository commentRepository;
    private final WorkflowMapper workflowMapper;

    /**
     * 申請のコメント一覧を取得する。
     *
     * @param requestId 申請ID
     * @return コメントレスポンスリスト
     */
    public List<WorkflowCommentResponse> listComments(Long requestId) {
        List<WorkflowRequestCommentEntity> comments =
                commentRepository.findByRequestIdOrderByCreatedAtAsc(requestId);
        return workflowMapper.toCommentResponseList(comments);
    }

    /**
     * コメントを作成する。
     *
     * @param requestId 申請ID
     * @param userId    ユーザーID
     * @param request   コメントリクエスト
     * @return 作成されたコメントレスポンス
     */
    @Transactional
    public WorkflowCommentResponse createComment(Long requestId, Long userId, WorkflowCommentRequest request) {
        WorkflowRequestCommentEntity entity = WorkflowRequestCommentEntity.builder()
                .requestId(requestId)
                .userId(userId)
                .body(request.getBody())
                .build();

        WorkflowRequestCommentEntity saved = commentRepository.save(entity);
        log.info("ワークフローコメント作成: requestId={}, commentId={}", requestId, saved.getId());
        return workflowMapper.toCommentResponse(saved);
    }

    /**
     * コメントを更新する。
     *
     * @param requestId 申請ID
     * @param commentId コメントID
     * @param request   コメントリクエスト
     * @return 更新されたコメントレスポンス
     */
    @Transactional
    public WorkflowCommentResponse updateComment(Long requestId, Long commentId, WorkflowCommentRequest request) {
        WorkflowRequestCommentEntity entity = commentRepository.findByIdAndRequestId(commentId, requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.COMMENT_NOT_FOUND));

        entity.updateBody(request.getBody());
        WorkflowRequestCommentEntity saved = commentRepository.save(entity);
        log.info("ワークフローコメント更新: commentId={}", commentId);
        return workflowMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     *
     * @param requestId 申請ID
     * @param commentId コメントID
     */
    @Transactional
    public void deleteComment(Long requestId, Long commentId) {
        WorkflowRequestCommentEntity entity = commentRepository.findByIdAndRequestId(commentId, requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.COMMENT_NOT_FOUND));

        entity.softDelete();
        commentRepository.save(entity);
        log.info("ワークフローコメント削除: commentId={}", commentId);
    }
}
