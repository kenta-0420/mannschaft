package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ReReviewStatus;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.repository.UserViolationRepository;
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

    /**
     * 認可根治戦役 Wave3-B3 BOLA 是正: {@code createReReview} の actionId 所有者検証に使用する。
     * user_violations は report_actions（WARNING）の実際の対象ユーザーを保持する唯一の窓口であり、
     * {@link com.mannschaft.app.moderation.entity.ReportActionEntity} 自体には対象ユーザーの
     * フィールドが無い（{@code actionBy} はレビュアー ID であり対象ユーザーではない）。
     */
    private final UserViolationRepository userViolationRepository;

    private final ModerationExtMapper mapper;

    /**
     * WARNING再レビュー依頼を作成する。
     *
     * <p>認可根治戦役 Wave3-B3 BOLA 是正: {@code actionId} は URL パスから直接指定される ID であり、
     * 従来は所有者検証なしに任意の {@code actionId}/{@code reportId} を指定して再レビューを
     * 作成できてしまっていた（他ユーザーの WARNING に対して再レビューを起票できる IDOR）。
     * {@link UserViolationService#selfCorrect} と同一の慣例（{@code user_violations.action_id} で
     * 対象ユーザーを解決し、呼び出し元と不一致なら存在秘匿のため 404 を返す）に揃える。
     * {@code reportId} も violation 由来の値と一致することを検証し、無関係な reportId を
     * 紐付けさせない（defense-in-depth）。</p>
     *
     * @param userId   ユーザーID
     * @param actionId アクションID
     * @param reportId 通報ID
     * @param reason   再レビュー理由
     * @return 再レビューレスポンス
     */
    @Transactional
    public WarningReReviewResponse createReReview(Long userId, Long actionId, Long reportId, String reason) {
        UserViolationEntity violation = userViolationRepository.findByActionId(actionId);
        if (violation == null || !violation.getUserId().equals(userId)
                || !violation.getReportId().equals(reportId)) {
            throw new BusinessException(ModerationExtErrorCode.VIOLATION_NOT_FOUND);
        }

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

        ReReviewStatus newStatus;
        try {
            newStatus = ReReviewStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS);
        }
        if (newStatus != ReReviewStatus.OVERTURNED && newStatus != ReReviewStatus.UPHELD
                && newStatus != ReReviewStatus.ESCALATED) {
            throw new BusinessException(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS);
        }
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
