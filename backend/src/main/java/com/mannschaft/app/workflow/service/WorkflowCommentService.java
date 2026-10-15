package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowScopes;
import com.mannschaft.app.workflow.dto.WorkflowCommentRequest;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestCommentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ワークフローコメントサービス。申請に対するコメントのCRUDを担当する。
 *
 * <p>認可（Wave 2 トランシェ2C）: コメント API の URL はスコープを含まないため、
 * 親申請を fetch し、entity 由来の scopeType/scopeId で「申請者本人 or スコープメンバー/ADMIN」を
 * 検証する。非所属者には 404（REQUEST_NOT_FOUND）で存在秘匿する（コメント越境の根治）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowCommentService {

    private final WorkflowRequestCommentRepository commentRepository;
    private final WorkflowRequestRepository requestRepository;
    private final WorkflowMapper workflowMapper;
    private final AccessControlService accessControlService;

    /**
     * 申請のコメント一覧を取得する。
     *
     * <p>認可: 申請者本人、または申請スコープのメンバー/ADMIN のみ（それ以外は 404 秘匿）。</p>
     *
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     * @return コメントレスポンスリスト
     */
    public List<WorkflowCommentResponse> listComments(Long requestId, Long actorUserId) {
        findVisibleRequestOrThrow(requestId, actorUserId);
        List<WorkflowRequestCommentEntity> comments =
                commentRepository.findByRequestIdOrderByCreatedAtAsc(requestId);
        return workflowMapper.toCommentResponseList(comments);
    }

    /**
     * コメントを作成する。
     *
     * <p>認可: 申請者本人、または申請スコープのメンバー/ADMIN のみ（それ以外は 404 秘匿）。</p>
     *
     * @param requestId 申請ID
     * @param userId    ユーザーID（操作者）
     * @param request   コメントリクエスト
     * @return 作成されたコメントレスポンス
     */
    @Transactional
    public WorkflowCommentResponse createComment(Long requestId, Long userId, WorkflowCommentRequest request) {
        findVisibleRequestOrThrow(requestId, userId);
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
     * <p>認可: コメント作成者本人のみ（ADMIN でも他人のコメント本文は改変不可 → 403）。</p>
     *
     * @param requestId   申請ID
     * @param commentId   コメントID
     * @param actorUserId 操作者ユーザーID
     * @param request     コメントリクエスト
     * @return 更新されたコメントレスポンス
     */
    @Transactional
    public WorkflowCommentResponse updateComment(Long requestId, Long commentId, Long actorUserId,
                                                 WorkflowCommentRequest request) {
        findVisibleRequestOrThrow(requestId, actorUserId);
        WorkflowRequestCommentEntity entity = commentRepository.findByIdAndRequestId(commentId, requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.COMMENT_NOT_FOUND));

        if (actorUserId == null || !actorUserId.equals(entity.getUserId())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        entity.updateBody(request.getBody());
        WorkflowRequestCommentEntity saved = commentRepository.save(entity);
        log.info("ワークフローコメント更新: commentId={}", commentId);
        return workflowMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     *
     * <p>認可: コメント作成者本人、または申請スコープの ADMIN/DEPUTY_ADMIN のみ（403）。</p>
     *
     * @param requestId   申請ID
     * @param commentId   コメントID
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public void deleteComment(Long requestId, Long commentId, Long actorUserId) {
        WorkflowRequestEntity requestEntity = findVisibleRequestOrThrow(requestId, actorUserId);
        WorkflowRequestCommentEntity entity = commentRepository.findByIdAndRequestId(commentId, requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.COMMENT_NOT_FOUND));

        accessControlService.checkOwnerOrAdmin(
                actorUserId, entity.getUserId(),
                requestEntity.getScopeId(), WorkflowScopes.canonical(requestEntity.getScopeType()));

        entity.softDelete();
        commentRepository.save(entity);
        log.info("ワークフローコメント削除: commentId={}", commentId);
    }

    /**
     * 親申請を取得し、可視性（申請者本人 or entity 由来スコープのメンバー/ADMIN）を検証する。
     * いずれでもない場合は 404（REQUEST_NOT_FOUND）で存在秘匿する（★BOLA厳禁★）。
     */
    private WorkflowRequestEntity findVisibleRequestOrThrow(Long requestId, Long actorUserId) {
        WorkflowRequestEntity requestEntity = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND));
        if (actorUserId != null && actorUserId.equals(requestEntity.getRequestedBy())) {
            return requestEntity;
        }
        String canonicalScope = WorkflowScopes.canonical(requestEntity.getScopeType());
        if (accessControlService.isMember(actorUserId, requestEntity.getScopeId(), canonicalScope)
                || accessControlService.isAdminOrAbove(actorUserId, requestEntity.getScopeId(), canonicalScope)) {
            return requestEntity;
        }
        throw new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND);
    }
}
