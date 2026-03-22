package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ReReviewStatus;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.repository.WarningReReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WARNING再レビューサービス。2段階再レビューフロー（ADMIN→SYSTEM_ADMIN昇格）を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarningReReviewService {

    private final WarningReReviewRepository reReviewRepository;
    private final ModerationExtMapper mapper;

    /**
     * WARNING再レビュー依頼を作成する。
     *
     * @param userId   ユーザーID
     * @param actionId アクションID
     * @param reportId 通報ID
     * @param reason   再レビュー理由
     * @return 再レビューレスポンス
     */
    @Transactional
    public WarningReReviewResponse createReReview(Long userId, Long actionId, Long reportId, String reason) {
        if (reReviewRepository.existsByUserIdAndActionId(userId, actionId)) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_ALREADY_EXISTS);
        }

        WarningReReviewEntity entity = WarningReReviewEntity.builder()
                .userId(userId)
                .reportId(reportId)
                .actionId(actionId)
                .reason(reason)
                .build();

        entity = reReviewRepository.save(entity);

        log.info("WARNING再レビュー作成: id={}, userId={}, actionId={}", entity.getId(), userId, actionId);
        return mapper.toWarningReReviewResponse(entity);
    }

    /**
     * ADMINが再レビューを判定する。
     *
     * @param id         再レビューID
     * @param status     新ステータス（OVERTURNED/UPHELD/ESCALATED）
     * @param reviewNote レビューメモ
     * @param reviewerId レビュアーID
     * @return 更新後の再レビューレスポンス
     */
    @Transactional
    public WarningReReviewResponse adminReview(Long id, String status, String reviewNote, Long reviewerId) {
        WarningReReviewEntity entity = reReviewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));

        if (entity.getStatus() != ReReviewStatus.PENDING) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS);
        }

        ReReviewStatus newStatus = ReReviewStatus.valueOf(status);
        entity.adminReview(reviewerId, reviewNote, newStatus);
        reReviewRepository.save(entity);

        log.info("ADMIN再レビュー判定: id={}, newStatus={}, reviewerId={}", id, newStatus, reviewerId);
        return mapper.toWarningReReviewResponse(entity);
    }

    /**
     * 再レビューをSYSTEM_ADMINに昇格する。
     *
     * @param id               再レビューID
     * @param escalationReason 昇格理由
     * @return 更新後の再レビューレスポンス
     */
    @Transactional
    public WarningReReviewResponse escalate(Long id, String escalationReason) {
        WarningReReviewEntity entity = reReviewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));

        if (entity.getStatus() != ReReviewStatus.PENDING && entity.getStatus() != ReReviewStatus.UPHELD) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS);
        }

        entity.escalate(escalationReason);
        reReviewRepository.save(entity);

        log.info("再レビュー昇格: id={}, reason={}", id, escalationReason);
        return mapper.toWarningReReviewResponse(entity);
    }

    /**
     * SYSTEM_ADMINが最終判定する。
     *
     * @param id         再レビューID
     * @param status     新ステータス（APPEAL_ACCEPTED/APPEAL_REJECTED）
     * @param reviewNote レビューメモ
     * @param reviewerId レビュアーID
     * @return 更新後の再レビューレスポンス
     */
    @Transactional
    public WarningReReviewResponse systemAdminReview(Long id, String status, String reviewNote, Long reviewerId) {
        WarningReReviewEntity entity = reReviewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));

        if (entity.getStatus() != ReReviewStatus.ESCALATED) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS);
        }

        ReReviewStatus newStatus = ReReviewStatus.valueOf(status);
        entity.systemAdminReview(reviewerId, reviewNote, newStatus);
        reReviewRepository.save(entity);

        log.info("SYSTEM_ADMIN最終判定: id={}, newStatus={}, reviewerId={}", id, newStatus, reviewerId);
        return mapper.toWarningReReviewResponse(entity);
    }

    /**
     * PENDING状態の再レビュー一覧を取得する。
     *
     * @param pageable ページング情報
     * @return ページング済み再レビュー一覧
     */
    public Page<WarningReReviewResponse> getPendingReReviews(Pageable pageable) {
        return reReviewRepository.findByStatusOrderByCreatedAtDesc(ReReviewStatus.PENDING, pageable)
                .map(mapper::toWarningReReviewResponse);
    }

    /**
     * ESCALATED状態の再レビュー一覧を取得する。
     *
     * @param pageable ページング情報
     * @return ページング済み再レビュー一覧
     */
    public Page<WarningReReviewResponse> getEscalatedReReviews(Pageable pageable) {
        return reReviewRepository.findByStatusOrderByCreatedAtDesc(ReReviewStatus.ESCALATED, pageable)
                .map(mapper::toWarningReReviewResponse);
    }

    /**
     * PENDING/ESCALATED各状態の件数を取得する。
     */
    public long countPendingReReviews() {
        return reReviewRepository.countByStatus(ReReviewStatus.PENDING);
    }

    public long countEscalatedReReviews() {
        return reReviewRepository.countByStatus(ReReviewStatus.ESCALATED);
    }
}
