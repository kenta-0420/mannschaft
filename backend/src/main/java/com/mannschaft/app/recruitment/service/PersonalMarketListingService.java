package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalMarketListingService {

    private final RecruitmentListingService listingService;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentMapper mapper;

    @Transactional
    public RecruitmentListingResponse create(Long currentUserId, CreateRecruitmentListingRequest request) {
        return listingService.create(
                RecruitmentScopeType.PERSONAL, currentUserId, currentUserId, request);
    }

    public Page<RecruitmentListingSummaryResponse> list(
            Long currentUserId, String status, Pageable pageable) {
        if (status == null) {
            return listingRepository
                    .findByScopeTypeAndScopeIdOrderByStartAtDesc(
                            RecruitmentScopeType.PERSONAL, currentUserId, pageable)
                    .map(mapper::toListingSummaryResponse);
        }
        return listingRepository
                .findByScopeTypeAndScopeIdAndStatusOrderByStartAtDesc(
                        RecruitmentScopeType.PERSONAL,
                        currentUserId,
                        RecruitmentListingStatus.valueOf(status),
                        pageable)
                .map(mapper::toListingSummaryResponse);
    }

    @Transactional
    public RecruitmentListingResponse update(Long currentUserId, Long listingId,
            UpdateRecruitmentListingRequest request) {
        lockDraftOrThrow(currentUserId, listingId);
        return listingService.update(listingId, currentUserId, request);
    }

    @Transactional
    public RecruitmentListingResponse cancel(Long currentUserId, Long listingId,
            CancelRecruitmentListingRequest request) {
        lockDraftOrThrow(currentUserId, listingId);
        return listingService.cancelByAdmin(listingId, currentUserId, request);
    }

    private void lockDraftOrThrow(Long currentUserId, Long listingId) {
        var listing = listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                listingId, RecruitmentScopeType.PERSONAL, currentUserId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND));
        if (listing.getStatus() != RecruitmentListingStatus.DRAFT) {
            throw new BusinessException(com.mannschaft.app.recruitment.RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
