package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.ActivityType;
import com.mannschaft.app.matching.MatchCategory;
import com.mannschaft.app.matching.MatchLevel;
import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.MatchVisibility;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.ActivitySuggestionResponse;
import com.mannschaft.app.matching.dto.CreateMatchRequestRequest;
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.dto.TeamSummaryResponse;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 募集投稿サービス。募集のCRUD・検索・期限切れバッチを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchRequestService {

    private static final int REVIEW_RETENTION_YEARS = 2;
    private static final int MIN_REVIEW_COUNT_FOR_SCORE = 5;

    private final MatchRequestRepository requestRepository;
    private final MatchProposalRepository proposalRepository;
    private final MatchReviewRepository reviewRepository;
    private final NgTeamRepository ngTeamRepository;

    /**
     * 募集を検索する（パブリック検索）。
     */
    public Page<MatchRequestResponse> searchRequests(Long currentTeamId,
                                                     String prefectureCode, String cityCode,
                                                     String activityTypeStr, String categoryStr,
                                                     String levelStr, String visibilityStr,
                                                     Pageable pageable) {
        List<Long> excludedTeamIds = ngTeamRepository.findBidirectionalBlockedTeamIds(currentTeamId);
        if (excludedTeamIds.isEmpty()) {
            excludedTeamIds = List.of(-1L); // JPA IN clause requires non-empty list
        }

        ActivityType activityType = activityTypeStr != null ? ActivityType.valueOf(activityTypeStr) : null;
        MatchCategory category = categoryStr != null ? MatchCategory.valueOf(categoryStr) : null;
        MatchLevel level = levelStr != null ? MatchLevel.valueOf(levelStr) : null;
        MatchVisibility visibility = visibilityStr != null ? MatchVisibility.valueOf(visibilityStr) : null;

        Page<MatchRequestEntity> page = requestRepository.searchRequests(
                MatchRequestStatus.OPEN, excludedTeamIds,
                prefectureCode, cityCode, activityType, category, level, visibility,
                LocalDateTime.now(), pageable);

        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        return page.map(entity -> toResponse(entity, since));
    }

    /**
     * キーワード検索する。
     */
    public Page<MatchRequestResponse> searchByKeyword(Long currentTeamId, String keyword, Pageable pageable) {
        List<Long> excludedTeamIds = ngTeamRepository.findBidirectionalBlockedTeamIds(currentTeamId);
        if (excludedTeamIds.isEmpty()) {
            excludedTeamIds = List.of(-1L);
        }

        Page<MatchRequestEntity> page = requestRepository.searchByKeyword(
                MatchRequestStatus.OPEN.name(), excludedTeamIds, keyword, LocalDateTime.now(), pageable);

        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        return page.map(entity -> toResponse(entity, since));
    }

    /**
     * 募集詳細を取得する。
     */
    public MatchRequestResponse getRequest(Long id, Long currentTeamId) {
        MatchRequestEntity entity = findRequestOrThrow(id);

        // NGチームチェック
        List<Long> blockedIds = ngTeamRepository.findBidirectionalBlockedTeamIds(currentTeamId);
        if (blockedIds.contains(entity.getTeamId())) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND);
        }

        // 他チームのOPEN以外は404
        if (!entity.getTeamId().equals(currentTeamId) && entity.getStatus() != MatchRequestStatus.OPEN) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND);
        }

        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        return toResponse(entity, since);
    }

    /**
     * 自チームの募集一覧を取得する。
     */
    public Page<MatchRequestResponse> listTeamRequests(Long teamId, Pageable pageable) {
        Page<MatchRequestEntity> page = requestRepository.findByTeamIdOrderByCreatedAtDesc(teamId, pageable);
        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        return page.map(entity -> toResponse(entity, since));
    }

    /**
     * 募集を作成する。
     */
    @Transactional
    public MatchRequestCreateResponse createRequest(Long teamId, CreateMatchRequestRequest request) {
        validateRequest(request);

        MatchCategory category = request.getCategory() != null
                ? MatchCategory.valueOf(request.getCategory()) : MatchCategory.ANY;
        MatchLevel level = request.getLevel() != null
                ? MatchLevel.valueOf(request.getLevel()) : MatchLevel.ANY;
        MatchVisibility visibility = request.getVisibility() != null
                ? MatchVisibility.valueOf(request.getVisibility()) : MatchVisibility.PLATFORM;

        MatchRequestEntity entity = MatchRequestEntity.builder()
                .teamId(teamId)
                .title(request.getTitle())
                .description(request.getDescription())
                .activityType(ActivityType.valueOf(request.getActivityType()))
                .activityDetail(request.getActivityDetail())
                .category(category)
                .visibility(visibility)
                .prefectureCode(request.getPrefectureCode())
                .cityCode(request.getCityCode())
                .venueName(request.getVenueName())
                .preferredDateFrom(request.getPreferredDateFrom())
                .preferredDateTo(request.getPreferredDateTo())
                .preferredTimeFrom(request.getPreferredTimeFrom())
                .preferredTimeTo(request.getPreferredTimeTo())
                .level(level)
                .minParticipants(request.getMinParticipants())
                .maxParticipants(request.getMaxParticipants())
                .expiresAt(request.getExpiresAt())
                .build();

        MatchRequestEntity saved = requestRepository.save(entity);
        log.info("募集作成: teamId={}, requestId={}", teamId, saved.getId());
        return new MatchRequestCreateResponse(saved.getId(), saved.getStatus().name());
    }

    /**
     * 募集を更新する。
     */
    @Transactional
    public MatchRequestResponse updateRequest(Long id, Long teamId, CreateMatchRequestRequest request) {
        MatchRequestEntity entity = findRequestOrThrow(id);

        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
        if (!entity.isEditable()) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_EDITABLE);
        }

        validateRequest(request);

        MatchCategory category = request.getCategory() != null
                ? MatchCategory.valueOf(request.getCategory()) : MatchCategory.ANY;
        MatchLevel level = request.getLevel() != null
                ? MatchLevel.valueOf(request.getLevel()) : MatchLevel.ANY;
        MatchVisibility visibility = request.getVisibility() != null
                ? MatchVisibility.valueOf(request.getVisibility()) : MatchVisibility.PLATFORM;

        entity.update(
                request.getTitle(), request.getDescription(),
                ActivityType.valueOf(request.getActivityType()), request.getActivityDetail(),
                category, visibility,
                request.getPrefectureCode(), request.getCityCode(), request.getVenueName(),
                request.getPreferredDateFrom(), request.getPreferredDateTo(),
                request.getPreferredTimeFrom(), request.getPreferredTimeTo(),
                level, request.getMinParticipants(), request.getMaxParticipants(),
                request.getExpiresAt());

        MatchRequestEntity saved = requestRepository.save(entity);
        log.info("募集更新: requestId={}", id);
        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        return toResponse(saved, since);
    }

    /**
     * 募集を論理削除する。
     */
    @Transactional
    public void deleteRequest(Long id, Long teamId) {
        MatchRequestEntity entity = findRequestOrThrow(id);

        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
        if (entity.getStatus() == MatchRequestStatus.MATCHED) {
            throw new BusinessException(MatchingErrorCode.REQUEST_MATCHED_CANNOT_DELETE);
        }

        // PENDING応募を一括REJECTED
        var pendingProposals = proposalRepository.findByRequestIdAndStatus(
                id, com.mannschaft.app.matching.MatchProposalStatus.PENDING);
        for (var proposal : pendingProposals) {
            proposal.reject("募集が取り下げられました");
            proposalRepository.save(proposal);
        }

        entity.softDelete();
        requestRepository.save(entity);
        log.info("募集削除: requestId={}", id);
    }

    /**
     * activity_detail のサジェストを取得する。
     */
    public List<ActivitySuggestionResponse> getActivitySuggestions(String query, String activityType) {
        List<Object[]> results = requestRepository.findActivitySuggestions(query, activityType);
        List<ActivitySuggestionResponse> suggestions = new ArrayList<>();
        for (Object[] row : results) {
            suggestions.add(new ActivitySuggestionResponse(
                    (String) row[0],
                    ((Number) row[1]).longValue()));
        }
        return suggestions;
    }

    /**
     * 募集エンティティを取得する。存在しない場合は例外をスローする。
     */
    MatchRequestEntity findRequestOrThrow(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
    }

    /**
     * 悲観ロック付きで募集エンティティを取得する。
     */
    MatchRequestEntity findRequestForUpdateOrThrow(Long id) {
        return requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
    }

    private void validateRequest(CreateMatchRequestRequest request) {
        if (request.getPreferredDateFrom() != null && request.getPreferredDateTo() != null
                && request.getPreferredDateFrom().isAfter(request.getPreferredDateTo())) {
            throw new BusinessException(MatchingErrorCode.INVALID_DATE_RANGE);
        }
        if (request.getMinParticipants() != null && request.getMaxParticipants() != null
                && request.getMinParticipants() > request.getMaxParticipants()) {
            throw new BusinessException(MatchingErrorCode.INVALID_PARTICIPANT_RANGE);
        }
    }

    private MatchRequestResponse toResponse(MatchRequestEntity entity, LocalDateTime since) {
        // チーム評価情報を動的集計
        Double avgRating = reviewRepository.findAverageRating(entity.getTeamId(), since);
        long reviewCount = reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(entity.getTeamId(), since);
        long cancelCount = proposalRepository.countCancellationsByTeam(entity.getTeamId(), since);

        Double displayRating = reviewCount >= MIN_REVIEW_COUNT_FOR_SCORE ? avgRating : null;

        TeamSummaryResponse teamSummary = new TeamSummaryResponse(
                entity.getTeamId(), null, displayRating, reviewCount, cancelCount);

        return new MatchRequestResponse(
                entity.getId(), teamSummary, entity.getTitle(), entity.getDescription(),
                entity.getActivityType().name(), entity.getActivityDetail(),
                entity.getCategory().name(), entity.getVisibility().name(),
                entity.getPrefectureCode(), entity.getCityCode(), entity.getVenueName(),
                entity.getPreferredDateFrom(), entity.getPreferredDateTo(),
                entity.getPreferredTimeFrom(), entity.getPreferredTimeTo(),
                entity.getLevel().name(), entity.getMinParticipants(), entity.getMaxParticipants(),
                entity.getStatus().name(), entity.getProposalCount(),
                entity.getExpiresAt(), entity.getCancelCount(),
                entity.getCreatedAt());
    }
}
