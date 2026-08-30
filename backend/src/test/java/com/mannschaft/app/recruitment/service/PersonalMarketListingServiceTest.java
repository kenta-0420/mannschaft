package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("個人市Serviceの本人固定契約")
class PersonalMarketListingServiceTest {

    @Mock
    private RecruitmentListingService listingService;

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentMapper mapper;

    @InjectMocks
    private PersonalMarketListingService service;

    @Test
    @DisplayName("作成時のスコープと札主を認証済み本人へ固定する")
    void create_bindsCurrentUserAsScopeAndOwner() {
        Long currentUserId = 123L;

        service.create(currentUserId, Mockito.mock(CreateRecruitmentListingRequest.class));

        verify(listingService).create(
                eq(RecruitmentScopeType.PERSONAL),
                eq(currentUserId),
                eq(currentUserId),
                any(CreateRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("DRAFT編集は認証済み本人を固定して共通サービスへ委譲する")
    void update_bindsCurrentUser() {
        Long currentUserId = 123L;
        Long listingId = 456L;
        RecruitmentListingResponse response = Mockito.mock(RecruitmentListingResponse.class);
        given(listingService.updatePersonalDraft(eq(listingId), eq(currentUserId),
                any(UpdateRecruitmentListingRequest.class))).willReturn(response);

        service.update(currentUserId, listingId, Mockito.mock(UpdateRecruitmentListingRequest.class));

        verify(listingService).updatePersonalDraft(eq(listingId), eq(currentUserId),
                any(UpdateRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("DRAFT取消は認証済み本人を固定して共通サービスへ委譲する")
    void cancel_bindsCurrentUser() {
        Long currentUserId = 123L;
        Long listingId = 456L;

        service.cancel(currentUserId, listingId, Mockito.mock(CancelRecruitmentListingRequest.class));

        verify(listingService).cancelPersonalDraft(eq(listingId), eq(currentUserId),
                any(CancelRecruitmentListingRequest.class));
    }
}
