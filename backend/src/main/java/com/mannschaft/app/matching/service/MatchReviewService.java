package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.CreateReviewRequest;
import com.mannschaft.app.matching.dto.ReviewCreateResponse;
import com.mannschaft.app.matching.dto.ReviewResponse;
import com.mannschaft.app.matching.dto.TeamReviewSummaryResponse;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.entity.MatchReviewEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * レビューサービス。レビューの作成・取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchReviewService {

    private static final int REVIEW_RETENTION_YEARS = 2;
    private static final int REVIEW_PERIOD_DAYS = 30;
    private static final int MIN_REVIEW_COUNT_FOR_SCORE = 5;

    private final MatchReviewRepository reviewRepository;
    private final MatchProposalRepository proposalRepository;
    private final MatchRequestRepository requestRepository;
    private final MatchingMapper matchingMapper;

    /**
     * レビューを作成する。
     */
    @Transactional
    public ReviewCreateResponse createReview(Long teamId, CreateReviewRequest request) {
        MatchProposalEntity proposal = proposalRepository.findById(request.getProposalId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND));

        // ACCEPTED チェック
        if (proposal.getStatus() != MatchProposalStatus.ACCEPTED) {
            throw new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }

        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        // 関与チームチェック
        Long requestingTeamId = matchRequest.getTeamId();
        Long proposingTeamId = proposal.getProposingTeamId();
        if (!teamId.equals(requestingTeamId) && !teamId.equals(proposingTeamId)) {
            throw new BusinessException(MatchingErrorCode.REVIEW_NOT_PARTICIPANT);
        }

        // レビュー対象チーム（相手チーム）を決定
        Long revieweeTeamId = teamId.equals(requestingTeamId) ? proposingTeamId : requestingTeamId;

        // 重複チェック
        if (reviewRepository.existsByProposalIdAndReviewerTeamId(request.getProposalId(), teamId)) {
            throw new BusinessException(MatchingErrorCode.DUPLICATE_REVIEW);
        }

        // 期限チェック（成立から30日以内）
        if (proposal.getUpdatedAt().plusDays(REVIEW_PERIOD_DAYS).isBefore(LocalDateTime.now())) {
            throw new BusinessException(MatchingErrorCode.REVIEW_PERIOD_EXPIRED);
        }

        Boolean isPublic = request.getIsPublic() != null ? request.getIsPublic() : true;

        MatchReviewEntity entity = MatchReviewEntity.builder()
                .proposalId(request.getProposalId())
                .reviewerTeamId(teamId)
                .revieweeTeamId(revieweeTeamId)
                .rating(request.getRating())
                .comment(request.getComment())
                .isPublic(isPublic)
                .build();

        MatchReviewEntity saved = reviewRepository.save(entity);
        log.info("レビュー作成: reviewId={}, proposalId={}, reviewerTeamId={}", saved.getId(), request.getProposalId(), teamId);
        return new ReviewCreateResponse(saved.getId(), revieweeTeamId, saved.getRating());
    }

    /**
     * チームのレビュー一覧と平均評価を取得する。
     */
    public TeamReviewSummaryResponse getTeamReviews(Long teamId, Pageable pageable) {
        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);

        Double avgRating = reviewRepository.findAverageRating(teamId, since);
        long reviewCount = reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(teamId, since);

        Double displayRating = reviewCount >= MIN_REVIEW_COUNT_FOR_SCORE ? avgRating : null;

        Page<MatchReviewEntity> page = reviewRepository
                .findByRevieweeTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(teamId, since, pageable);

        List<ReviewResponse> reviews = page.getContent().stream()
                .map(matchingMapper::toReviewResponse)
                .toList();

        return new TeamReviewSummaryResponse(teamId, displayRating, reviewCount, reviews);
    }
}
