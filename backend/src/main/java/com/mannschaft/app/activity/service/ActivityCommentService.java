package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityCommentRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.AccessControlService;
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
    private final ActivityResultRepository resultRepository;
    private final ActivityMapper activityMapper;
    private final AccessControlService accessControlService;

    /**
     * コメント一覧を取得する。
     *
     * <p>認可: コメントが紐づく活動記録のスコープ会員のみ閲覧可（非会員は 403 = COMMON_002）。
     * 他スコープ会員によるコメント列挙（IDOR）を封じる。</p>
     */
    public List<ActivityCommentResponse> listComments(Long activityId, Long userId) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        checkScopeMembership(userId, activity);
        return activityMapper.toCommentResponseList(
                commentRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId));
    }

    /**
     * コメントを作成する。
     *
     * <p>認可: コメント対象の活動記録のスコープ会員のみ投稿可（非会員は 403 = COMMON_002）。</p>
     */
    @Transactional
    public ActivityCommentResponse createComment(Long activityId, Long userId, CreateCommentRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        checkScopeMembership(userId, activity);
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
     *
     * <p>認可: 本人または当該スコープの管理者（ADMIN/DEPUTY_ADMIN）のみ削除可。
     * 他スコープ会員・他人のコメントを削除しようとした場合は 403（COMMON_002）。
     * {@code updateComment} が本人限定であるのに対し、削除は運用上「本人 or 管理者」を許容する。</p>
     */
    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        ActivityCommentEntity entity = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.COMMENT_NOT_FOUND));

        // コメント → 活動記録 → スコープ を辿り、本人 or 管理者を検証
        ActivityResultEntity activity = findActivityOrThrow(entity.getActivityResultId());
        ActivityScopeType scopeType = activity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkOwnerOrAdmin(
                    userId, entity.getUserId(), activity.getScopeId(), scopeType.name());
        }

        entity.softDelete();
        commentRepository.save(entity);
        log.info("コメント削除: commentId={}", commentId);
    }

    /**
     * 活動記録エンティティを取得する。存在しない場合は例外をスローする。
     */
    private ActivityResultEntity findActivityOrThrow(Long activityId) {
        return resultRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND));
    }

    /**
     * 活動記録のスコープ会員であることを検証する。
     *
     * <p>{@code ActivityResultService} の既存実装に倣い、TEAM/ORGANIZATION のみ検証する
     * （AccessControlService は TEAM/ORGANIZATION のみ処理するため。COMMITTEE は対象外で通す）。</p>
     */
    private void checkScopeMembership(Long userId, ActivityResultEntity activity) {
        ActivityScopeType scopeType = activity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, activity.getScopeId(), scopeType.name());
        }
    }
}
