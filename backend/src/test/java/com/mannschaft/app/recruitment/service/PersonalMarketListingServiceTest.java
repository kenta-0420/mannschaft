package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalMarketListingService contract")
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
    @DisplayName("create fixes scope and owner to the current user")
    void create_bindsCurrentUserAsScopeAndOwner() {
        Long currentUserId = 123L;

        service.create(currentUserId, Mockito.mock(CreateRecruitmentListingRequest.class));

        verify(listingService).create(
                eq(RecruitmentScopeType.PERSONAL),
                eq(currentUserId),
                eq(currentUserId),
                any(CreateRecruitmentListingRequest.class));
    }
}
