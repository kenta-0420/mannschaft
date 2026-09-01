package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.PersonalMarketMatchResponse;
import com.mannschaft.app.recruitment.dto.PersonalMarketAudienceScopeResponse;
import com.mannschaft.app.recruitment.dto.PersonalMarketListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalMarketListingService {

    private final RecruitmentListingService listingService;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentMapper mapper;
    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentListingAudienceScopeRepository audienceScopeRepository;

    @Transactional
    public RecruitmentListingResponse create(Long currentUserId, CreateRecruitmentListingRequest request) {
        return listingService.create(
                RecruitmentScopeType.PERSONAL, currentUserId, currentUserId, request);
    }

    public Page<PersonalMarketListingSummaryResponse> list(Long currentUserId, String status,
            String prefectureCode, String cityCode, Long categoryId, Pageable pageable) {
        RecruitmentListingStatus parsedStatus = status == null ? null : RecruitmentListingStatus.valueOf(status);
        Page<RecruitmentListingEntity> listings = listingRepository.findPersonalMarketListings(
                currentUserId, parsedStatus, prefectureCode, cityCode, categoryId, pageable);
        List<Long> listingIds = listings.stream().map(RecruitmentListingEntity::getId).toList();
        Map<Long, List<PersonalMarketAudienceScopeResponse>> audienceScopesByListingId =
                (listingIds.isEmpty() ? List.<com.mannschaft.app.recruitment.entity.RecruitmentListingAudienceScopeEntity>of()
                        : audienceScopeRepository.findByListingIdInOrderByListingIdAscIdAsc(listingIds))
                        .stream()
                        .collect(Collectors.groupingBy(
                                scope -> scope.getListingId(),
                                Collectors.mapping(scope -> new PersonalMarketAudienceScopeResponse(
                                        scope.getScopeType().name(), scope.getScopeId()), Collectors.toList())));
        return listings.map(listing -> toPersonalSummary(
                mapper.toListingSummaryResponse(listing),
                audienceScopesByListingId.getOrDefault(listing.getId(), List.of())));
    }

    private static PersonalMarketListingSummaryResponse toPersonalSummary(
            RecruitmentListingSummaryResponse source,
            List<PersonalMarketAudienceScopeResponse> audienceScopes) {
        return new PersonalMarketListingSummaryResponse(
                source.getId(), source.getCategoryId(), source.getCategoryNameI18nKey(), source.getTitle(),
                source.getParticipationType(), source.getStartAt(), source.getEndAt(),
                source.getApplicationDeadline(), source.getCapacity(), source.getMinCapacity(),
                source.getConfirmedCount(), source.getWaitlistCount(), source.getStatus(), source.getVisibility(),
                source.getLocation(), source.getImageUrl(), source.getPaymentEnabled(), source.getPrice(),
                audienceScopes);
    }

    public Page<PersonalMarketMatchResponse> listMatches(Long currentUserId, Long listingId, Pageable pageable) {
        listingRepository.findByIdAndScopeTypeAndScopeIdAndCreatedBy(
                listingId, RecruitmentScopeType.PERSONAL, currentUserId, currentUserId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND));
        return participantRepository.findByListingIdOrderByAppliedAtAsc(listingId, pageable)
                .map(participant -> new PersonalMarketMatchResponse(
                        participant.getId(), participant.getParticipantType(), participant.getStatus(),
                        participant.getWaitlistPosition(), toInstant(participant.getAppliedAt()),
                        toInstant(participant.getStatusChangedAt())));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    @Transactional
    public RecruitmentListingResponse update(Long currentUserId, Long listingId,
            UpdateRecruitmentListingRequest request) {
        return listingService.updatePersonalDraft(listingId, currentUserId, request);
    }

    @Transactional
    public RecruitmentListingResponse publish(Long currentUserId, Long listingId) {
        return listingService.publishPersonal(listingId, currentUserId);
    }

    @Transactional
    public RecruitmentListingResponse cancel(Long currentUserId, Long listingId,
            CancelRecruitmentListingRequest request) {
        return listingService.cancelPersonalListing(listingId, currentUserId, request);
    }
}
