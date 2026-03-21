package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.repository.ActivityCommentRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 活動コメントサービス。コメントのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityCommentService {

    private final ActivityCommentRepository commentRepository;
    private final ActivityMapper activityMapper;

    /**
     * コメント一覧を取得する。
     */
    public List<ActivityCommentResponse> listComments(Long activityId) {
        return activityMapper.toCommentResponseList(
                commentRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId));
    }

    /**
     * コメントを作成する。
     */
    @Transactional
    public ActivityCommentResponse createComment(Long activityId, Long userId, CreateCommentRequest request) {
        ActivityCommentEntity entity = ActivityCommentEntity.builder()
                .activityResultId(activityId)
                .userId(userId)
                .body(request.getBody())
                .build();

        ActivityCommentEntity saved = commentRepository.save(entity);
        log.info("コメント作成: commentId={}, activityId={}", saved.getId(), activityId);
        return activityMapper.toCommentResponse(saved);
    }

    /**
     * コメントを更新する。
     */
    @Transactional
    public ActivityCommentResponse updateComment(Long commentId, Long userId, UpdateCommentRequest request) {
        ActivityCommentEntity entity = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.COMMENT_NOT_FOUND));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(ActivityErrorCode.NOT_AUTHOR);
        }

        entity.update(request.getBody());
        ActivityCommentEntity saved = commentRepository.save(entity);
        log.info("コメント更新: commentId={}", commentId);
        return activityMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     */
    @Transactional
    public void deleteComment(Long commentId) {
        ActivityCommentEntity entity = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.COMMENT_NOT_FOUND));
        entity.softDelete();
        commentRepository.save(entity);
        log.info("コメント削除: commentId={}", commentId);
    }
}
