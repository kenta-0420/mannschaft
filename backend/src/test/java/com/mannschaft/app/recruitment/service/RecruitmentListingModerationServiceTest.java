package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RecruitmentListingModerationServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentListingAudienceScopeRepository audienceScopeRepository;
    @Mock
    private UserService userService;
    @Mock
    private RecruitmentListingRepository.ModerationListingProjection projection;
    @InjectMocks
    private RecruitmentListingModerationService service;

    @Test
    void publicPersonalListing_requiresEnabledPublicProfile() {
        givenVisibleProjection("PUBLIC");
        given(userService.getActiveMarketOwnerIdentities(Set.of(7L))).willReturn(Map.of(
                7L, new UserService.MarketOwnerIdentity(7L, "公開名", "実名", null, false, false)));

        assertThatThrownBy(() -> service.getReportTarget(100L, 9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ModerationErrorCode.REPORT_TARGET_NOT_FOUND));
    }

    @Test
    void selectedScopeListing_requiresCurrentAudienceMembership() {
        givenVisibleProjection("SELECTED_SCOPES");
        given(audienceScopeRepository.findAccessibleListingIds(9L)).willReturn(List.of(100L));

        var target = service.getReportTarget(100L, 9L);

        assertThat(target.ownerUserId()).isEqualTo(7L);
        assertThat(target.title()).isEqualTo("個人札");
    }

    private void givenVisibleProjection(String visibility) {
        given(listingRepository.findModerationListingById(100L)).willReturn(Optional.of(projection));
        given(projection.getScopeType()).willReturn("PERSONAL");
        given(projection.getScopeId()).willReturn(7L);
        org.mockito.Mockito.lenient().when(projection.getCreatedBy()).thenReturn(7L);
        org.mockito.Mockito.lenient().when(projection.getTitle()).thenReturn("個人札");
        given(projection.getVisibility()).willReturn(visibility);
        given(projection.getStatus()).willReturn("OPEN");
    }
}
