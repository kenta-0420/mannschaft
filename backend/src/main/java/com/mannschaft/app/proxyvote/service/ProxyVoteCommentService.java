package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.CommentResponse;
import com.mannschaft.app.proxyvote.dto.CreateCommentRequest;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionCommentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 議案コメントサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteCommentService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteMotionCommentRepository commentRepository;
    private final ProxyVoteMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * 議案コメント一覧を取得する。
     *
     * <p>認可根治戦役 Wave7: 兄弟の {@code ProxyVoteCastService#castVote} と同一の
     * {@link AccessControlService#checkMembership} を敷き、セッションスコープの会員に
     * 限定する。</p>
     */
    public Page<CommentResponse> listComments(Long motionId, Long currentUserId, Pageable pageable) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
        return commentRepository.findByMotionIdOrderByCreatedAtAsc(motionId, pageable)
                .map(mapper::toCommentResponse);
    }

    /**
     * 議案にコメントを投稿する。
     *
     * <p>認可根治戦役 Wave7: {@link #listComments} と同一の理由で
     * {@link AccessControlService#checkMembership} を敷く。</p>
     */
    @Transactional
    public CommentResponse createComment(Long motionId, CreateCommentRequest request, Long currentUserId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        ProxyVoteMotionCommentEntity comment = ProxyVoteMotionCommentEntity.builder()
                .motionId(motionId)
                .userId(currentUserId)
                .body(request.getBody())
                .build();
        comment = commentRepository.save(comment);

        log.info("コメント投稿: commentId={}, motionId={}", comment.getId(), motionId);
        return mapper.toCommentResponse(comment);
    }

    /**
     * コメントを論理削除する。
     */
    @Transactional
    public void deleteComment(Long motionId, Long commentId, Long currentUserId) {
        ProxyVoteMotionCommentEntity comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.COMMENT_NOT_FOUND));

        if (!comment.getMotionId().equals(motionId)) {
            throw new BusinessException(ProxyVoteErrorCode.COMMENT_NOT_FOUND);
        }

        // 本人またはADMIN/DEPUTY_ADMINが削除可能
        if (!comment.getUserId().equals(currentUserId)) {
            ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
            ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
            Long orgId = session.getOrganizationId();
            if (orgId == null || !accessControlService.isAdminOrAbove(currentUserId, orgId, "ORGANIZATION")) {
                throw new BusinessException(ProxyVoteErrorCode.NOT_COMMENT_OWNER);
            }
        }

        comment.softDelete();
        commentRepository.save(comment);
        log.info("コメント削除: commentId={}", commentId);
    }
}
