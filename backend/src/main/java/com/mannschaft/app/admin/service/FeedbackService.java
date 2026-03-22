package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminFeedbackErrorCode;
import com.mannschaft.app.admin.AnnouncementFeedbackMapper;
import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.FeedbackVoteEntity;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.repository.FeedbackVoteRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * フィードバック（目安箱）サービス。投稿・回答・投票を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackService {

    private final FeedbackSubmissionRepository feedbackRepository;
    private final FeedbackVoteRepository voteRepository;
    private final AnnouncementFeedbackMapper mapper;

    /**
     * フィードバックを投稿する。
     *
     * @param req    作成リクエスト
     * @param userId 投稿者ID
     * @return 作成されたフィードバック
     */
    @Transactional
    public FeedbackResponse createFeedback(CreateFeedbackRequest req, Long userId) {
        FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                .scopeType(req.getScopeType())
                .scopeId(req.getScopeId())
                .category(req.getCategory())
                .title(req.getTitle())
                .body(req.getBody())
                .isAnonymous(req.getIsAnonymous() != null ? req.getIsAnonymous() : false)
                .submittedBy(userId)
                .build();

        entity = feedbackRepository.save(entity);
        log.info("フィードバック投稿: id={}, scopeType={}, userId={}", entity.getId(), entity.getScopeType(), userId);
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバック一覧を取得する（スコープ別）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータス（nullなら全件）
     * @param pageable  ページネーション情報
     * @return フィードバックページ
     */
    public Page<FeedbackResponse> getFeedbacks(String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<FeedbackSubmissionEntity> page;
        if (status != null && !status.isBlank()) {
            page = feedbackRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, FeedbackStatus.valueOf(status), pageable);
        } else {
            page = feedbackRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(this::toResponseWithVoteCount);
    }

    /**
     * 自分のフィードバック一覧を取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページネーション情報
     * @return フィードバックページ
     */
    public Page<FeedbackResponse> getMyFeedbacks(Long userId, Pageable pageable) {
        return feedbackRepository.findBySubmittedByOrderByCreatedAtDesc(userId, pageable)
                .map(this::toResponseWithVoteCount);
    }

    /**
     * 管理者がフィードバックに回答する。
     *
     * @param id      フィードバックID
     * @param req     回答リクエスト
     * @param adminId 管理者ID
     * @return 回答後のフィードバック
     */
    @Transactional
    public FeedbackResponse respondToFeedback(Long id, FeedbackRespondRequest req, Long adminId) {
        FeedbackSubmissionEntity entity = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND));

        entity.respond(
                req.getAdminResponse(),
                adminId,
                req.getIsPublicResponse() != null ? req.getIsPublicResponse() : false
        );
        entity = feedbackRepository.save(entity);
        log.info("フィードバック回答: id={}, adminId={}", id, adminId);
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバックのステータスを変更する。
     *
     * @param id  フィードバックID
     * @param req ステータス変更リクエスト
     * @return 更新後のフィードバック
     */
    @Transactional
    public FeedbackResponse updateFeedbackStatus(Long id, FeedbackStatusRequest req) {
        FeedbackSubmissionEntity entity = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND));

        entity.changeStatus(FeedbackStatus.valueOf(req.getStatus()));
        entity = feedbackRepository.save(entity);
        log.info("フィードバックステータス変更: id={}, status={}", id, req.getStatus());
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバックに投票する。
     *
     * @param feedbackId フィードバックID
     * @param userId     ユーザーID
     */
    @Transactional
    public void vote(Long feedbackId, Long userId) {
        if (!feedbackRepository.existsById(feedbackId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND);
        }
        if (voteRepository.existsByFeedbackIdAndUserId(feedbackId, userId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_ALREADY_VOTED);
        }

        FeedbackVoteEntity vote = FeedbackVoteEntity.builder()
                .feedbackId(feedbackId)
                .userId(userId)
                .build();
        voteRepository.save(vote);
        log.info("フィードバック投票: feedbackId={}, userId={}", feedbackId, userId);
    }

    /**
     * フィードバックの投票を取り消す。
     *
     * @param feedbackId フィードバックID
     * @param userId     ユーザーID
     */
    @Transactional
    public void unvote(Long feedbackId, Long userId) {
        if (!voteRepository.existsByFeedbackIdAndUserId(feedbackId, userId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_VOTE_NOT_FOUND);
        }
        voteRepository.deleteByFeedbackIdAndUserId(feedbackId, userId);
        log.info("フィードバック投票取消: feedbackId={}, userId={}", feedbackId, userId);
    }

    /**
     * エンティティに投票数を付与してレスポンスを生成する。
     */
    private FeedbackResponse toResponseWithVoteCount(FeedbackSubmissionEntity entity) {
        long voteCount = voteRepository.countByFeedbackId(entity.getId());
        return new FeedbackResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getBody(),
                entity.getIsAnonymous(),
                entity.getSubmittedBy(),
                entity.getStatus().name(),
                entity.getAdminResponse(),
                entity.getRespondedBy(),
                entity.getRespondedAt(),
                entity.getIsPublicResponse(),
                voteCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
