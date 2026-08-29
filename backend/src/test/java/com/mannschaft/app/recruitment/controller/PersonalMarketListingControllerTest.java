package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.service.PersonalMarketListingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalMarketListingController contract")
class PersonalMarketListingControllerTest {

    private static final Long USER_ID = 701L;

    @Mock
    private PersonalMarketListingService personalMarketListingService;

    @InjectMocks
    private PersonalMarketListingController controller;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("PersonalMarketListingController#create binds the current user")
    void create_boundToCurrentUserOnly() {
        controller.create(Mockito.mock(CreateRecruitmentListingRequest.class));

        verify(personalMarketListingService).create(eq(USER_ID), any(CreateRecruitmentListingRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("PersonalMarketListingController#list binds the current user")
    void list_boundToCurrentUserOnly() {
        Page<RecruitmentListingSummaryResponse> page = Mockito.mock(Page.class);
        given(page.getTotalElements()).willReturn(0L);
        given(page.getNumber()).willReturn(0);
        given(page.getSize()).willReturn(20);
        given(page.getTotalPages()).willReturn(0);
        given(page.getContent()).willReturn(java.util.List.of());
        given(personalMarketListingService.list(eq(USER_ID), eq(null), any())).willReturn(page);

        controller.list(null, 0, 20);

        verify(personalMarketListingService).list(eq(USER_ID), eq(null), any());
    }
}
