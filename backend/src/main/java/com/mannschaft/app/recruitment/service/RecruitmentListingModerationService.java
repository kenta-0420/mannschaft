package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 募集札を通報・モデレーション境界から扱うための最小ファサード。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentListingModerationService {

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentListingAudienceScopeRepository audienceScopeRepository;
    private final UserService userService;

    public ListingReportTarget getReportTarget(Long listingId, Long reporterUserId) {
        RecruitmentListingRepository.ModerationListingProjection listing = listingRepository
                .findModerationListingById(listingId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_TARGET_NOT_FOUND));
        boolean active = ("OPEN".equals(listing.getStatus()) || "FULL".equals(listing.getStatus()))
                && listing.getModerationHiddenAt() == null;
        boolean publicVisible = "PUBLIC".equals(listing.getVisibility());
        if (publicVisible && "PERSONAL".equals(listing.getScopeType())) {
            var owner = userService.getActiveMarketOwnerIdentities(java.util.Set.of(listing.getScopeId()))
                    .get(listing.getScopeId());
            publicVisible = owner != null && owner.publicProfileEnabled();
        }
        boolean selectedVisible = "SELECTED_SCOPES".equals(listing.getVisibility())
                && audienceScopeRepository.findAccessibleListingIds(reporterUserId).contains(listingId);
        if (!active || (!publicVisible && !selectedVisible)) {
            throw new BusinessException(ModerationErrorCode.REPORT_TARGET_NOT_FOUND);
        }
        return new ListingReportTarget(listing.getScopeType(), listing.getScopeId(),
                listing.getCreatedBy(), listing.getTitle());
    }

    @Transactional
    public void hide(Long listingId) {
        if (listingRepository.hideForModeration(listingId) == 0) {
            throw new BusinessException(ModerationErrorCode.REPORT_TARGET_NOT_FOUND);
        }
    }

    @Transactional
    public void restore(Long listingId) {
        if (listingRepository.restoreFromModeration(listingId) == 0) {
            throw new BusinessException(ModerationErrorCode.REPORT_TARGET_NOT_FOUND);
        }
    }

    public record ListingReportTarget(String scopeType, Long scopeId, Long ownerUserId,
                                      String title) { }
}
