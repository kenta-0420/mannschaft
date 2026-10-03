package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;

    /**
     * レビューを作成する。
     *
     * <p><strong>認可根治（第2弾 (C)）:</strong> 従来は認証プリンシパル（userID）を teamId として
     * そのままレビュアーチーム扱いしていた（userID≠teamID の誤り。実質ほぼ常に
     * {@code REVIEW_NOT_PARTICIPANT} で弾かれるか、稀に userID==teamID の偶然一致で不正レビューが成立し得た）。</p>
     *
     * <p>正しくは「実際に対戦した 2 チーム（募集チーム / 応募チーム）のうち、
     * 現在ユーザーが管理者/副管理者であるチーム」をレビュアーチームとして解決する。
     * これにより (1) 対戦への参加 と (2) 管理者/副管理者権限 の両方を一度に検証し、
     * 他人の対戦への相乗り評価・一般メンバーによる投稿を封鎖する。
     * 募集チームと応募チームは必ず異なる（自チームの募集には応募不可）ため、
     * reviewer≠reviewee は構造的に保証される。</p>
     *
     * @param currentUserId 認証ユーザー ID（teamId ではない）
     * @param request       レビュー投稿内容
     */
    @Transactional
    public ReviewCreateResponse createReview(Long currentUserId, CreateReviewRequest request) {
        MatchProposalEntity proposal = proposalRepository.findById(request.getProposalId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND));

        // ACCEPTED チェック
        if (proposal.getStatus() != MatchProposalStatus.ACCEPTED) {
            throw new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }

        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        // 対戦した 2 チーム
        Long requestingTeamId = matchRequest.getTeamId();
        Long proposingTeamId = proposal.getProposingTeamId();

        // 認可: 現ユーザーが管理者/副管理者である「参加チーム」をレビュアーチームとして解決する。
        // どちらの参加チームの管理者/副管理者でもなければ、対戦非参加または権限不足としてレビュー不可。
        boolean adminOfRequesting = accessControlService.isAdminOrAbove(currentUserId, requestingTeamId, "TEAM");
        boolean adminOfProposing = accessControlService.isAdminOrAbove(currentUserId, proposingTeamId, "TEAM");
        if (!adminOfRequesting && !adminOfProposing) {
            throw new BusinessException(MatchingErrorCode.REVIEW_NOT_PARTICIPANT);
        }

        // レビュアーチームと、その相手（レビュー対象）チームを確定。
        Long reviewerTeamId = adminOfRequesting ? requestingTeamId : proposingTeamId;
        Long revieweeTeamId = adminOfRequesting ? proposingTeamId : requestingTeamId;

        // 重複チェック（同一 proposal・同一レビュアーチームで1件のみ）
        if (reviewRepository.existsByProposalIdAndReviewerTeamId(request.getProposalId(), reviewerTeamId)) {
            throw new BusinessException(MatchingErrorCode.DUPLICATE_REVIEW);
        }

        // 期限チェック（成立から30日以内）
        if (proposal.getUpdatedAt().plusDays(REVIEW_PERIOD_DAYS).isBefore(LocalDateTime.now())) {
            throw new BusinessException(MatchingErrorCode.REVIEW_PERIOD_EXPIRED);
        }

        Boolean isPublic = request.getIsPublic() != null ? request.getIsPublic() : true;

        MatchReviewEntity entity = MatchReviewEntity.builder()
                .proposalId(request.getProposalId())
                .reviewerTeamId(reviewerTeamId)
                .revieweeTeamId(revieweeTeamId)
                .rating(request.getRating())
                .comment(request.getComment())
                .isPublic(isPublic)
                .build();

        MatchReviewEntity saved = reviewRepository.save(entity);
        log.info("レビュー作成: reviewId={}, proposalId={}, reviewerTeamId={}, byUserId={}",
                saved.getId(), request.getProposalId(), reviewerTeamId, currentUserId);
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
