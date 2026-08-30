package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.PersonalMarketMatchResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalMarketListingService {

    private final RecruitmentListingService listingService;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentMapper mapper;
    private final RecruitmentParticipantRepository participantRepository;

    @Transactional
    public RecruitmentListingResponse create(Long currentUserId, CreateRecruitmentListingRequest request) {
        return listingService.create(
                RecruitmentScopeType.PERSONAL, currentUserId, currentUserId, request);
    }

    public Page<RecruitmentListingSummaryResponse> list(Long currentUserId, String status,
            String prefectureCode, String cityCode, Long categoryId, Pageable pageable) {
        RecruitmentListingStatus parsedStatus = status == null ? null : RecruitmentListingStatus.valueOf(status);
        return listingRepository.findPersonalMarketListings(
                currentUserId, parsedStatus, prefectureCode, cityCode, categoryId, pageable)
                .map(mapper::toListingSummaryResponse);
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
    public RecruitmentListingResponse cancel(Long currentUserId, Long listingId,
            CancelRecruitmentListingRequest request) {
        return listingService.cancelPersonalDraft(listingId, currentUserId, request);
    }
}
