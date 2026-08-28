package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.service.RecruitmentParticipantService;
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

import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentApplicationController} の単体テスト。
 *
 * <p>RecruitmentApplicationController#cancelMyApplication の自己スコープ性を固定する契約テスト。
 * {@code RecruitmentParticipantService#cancelMyApplication} は
 * {@code findActiveByListingAndUser(listingId, userId)} で
 * {@code SecurityUtils.getCurrentUserId()} の参加行のみを検索条件に束縛するため、
 * 他人の参加行へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentApplicationController 単体テスト")
class RecruitmentApplicationControllerTest {

    @Mock
    private RecruitmentParticipantService participantService;

    @InjectMocks
    private RecruitmentApplicationController controller;

    private static final Long USER_ID = 100L;
    private static final Long LISTING_ID = 200L;

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
    @DisplayName("cancelMyApplication は SecurityUtils.getCurrentUserId() のみを本人特定に渡す")
    void cancelMyApplication_boundToCurrentUserOnly() {
        CancelMyApplicationRequest request = new CancelMyApplicationRequest(true, null);

        controller.cancelMyApplication(LISTING_ID, request);

        // 他人の userId を本人特定に渡す経路が存在しないことの裏取り。
        verify(participantService).cancelMyApplication(LISTING_ID, USER_ID, request);
    }
}
