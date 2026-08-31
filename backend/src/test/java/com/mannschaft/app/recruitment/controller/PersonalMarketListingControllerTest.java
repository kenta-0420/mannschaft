package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.PersonalMarketMatchResponse;
import com.mannschaft.app.recruitment.dto.PersonalMarketListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
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
@DisplayName("個人市Controllerの本人固定契約")
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
    @DisplayName("作成は認証済み本人へ固定する")
    void create_boundToCurrentUserOnly() {
        controller.create(Mockito.mock(CreateRecruitmentListingRequest.class));

        verify(personalMarketListingService).create(eq(USER_ID), any(CreateRecruitmentListingRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("履歴一覧は認証済み本人へ固定する")
    void list_boundToCurrentUserOnly() {
        Page<PersonalMarketListingSummaryResponse> page = Mockito.mock(Page.class);
        given(page.getTotalElements()).willReturn(0L);
        given(page.getNumber()).willReturn(0);
        given(page.getSize()).willReturn(20);
        given(page.getTotalPages()).willReturn(0);
        given(page.getContent()).willReturn(java.util.List.of());
        given(personalMarketListingService.list(eq(USER_ID), eq(null), eq(null), eq(null), eq(null), any()))
                .willReturn(page);

        controller.list(null, null, null, null, 0, 20);

        verify(personalMarketListingService).list(eq(USER_ID), eq(null), eq(null), eq(null), eq(null), any());
    }

    /** PersonalMarketListingController#listMatches は認証済み本人の複合所有条件だけへ委譲する。 */
    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("PersonalMarketListingController#listMatches は認証済み本人へ固定する")
    void listMatches_boundToCurrentUserOnly() {
        Page<PersonalMarketMatchResponse> page = Mockito.mock(Page.class);
        given(page.getTotalElements()).willReturn(0L);
        given(page.getNumber()).willReturn(0);
        given(page.getSize()).willReturn(20);
        given(page.getTotalPages()).willReturn(0);
        given(page.getContent()).willReturn(java.util.List.of());
        given(personalMarketListingService.listMatches(eq(USER_ID), eq(42L), any())).willReturn(page);

        controller.listMatches(42L, 0, 20);

        verify(personalMarketListingService).listMatches(eq(USER_ID), eq(42L), any());
    }

    @Test
    @DisplayName("更新は認証済み本人に固定して委譲する")
    void update_boundToCurrentUserOnly() {
        controller.update(42L, Mockito.mock(UpdateRecruitmentListingRequest.class));

        verify(personalMarketListingService).update(eq(USER_ID), eq(42L),
                any(UpdateRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("公開は認証済み本人の個人札へ固定して委譲する")
    void publish_boundToCurrentUserOnly() {
        controller.publish(42L);

        verify(personalMarketListingService).publish(USER_ID, 42L);
    }

    @Test
    @DisplayName("取消は認証済み本人に固定して委譲する")
    void cancel_boundToCurrentUserOnly() {
        controller.cancel(42L, Mockito.mock(CancelRecruitmentListingRequest.class));

        verify(personalMarketListingService).cancel(eq(USER_ID), eq(42L),
                any(CancelRecruitmentListingRequest.class));
    }
}
