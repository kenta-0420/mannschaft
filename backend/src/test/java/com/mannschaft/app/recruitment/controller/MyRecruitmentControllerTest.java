package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
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
 * {@link MyRecruitmentController} の単体テスト。
 *
 * <p>MyRecruitmentController#myActiveParticipations / #myFeed の自己スコープ性を固定する
 * 契約テスト。いずれも {@code RecruitmentListingService} が
 * {@code SecurityUtils.getCurrentUserId()} のみを検索条件に束縛するため、
 * 他人の参加履歴・フォロー先フィードへ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyRecruitmentController 単体テスト")
class MyRecruitmentControllerTest {

    @Mock
    private RecruitmentParticipantService participantService;

    @Mock
    private RecruitmentListingService listingService;

    @InjectMocks
    private MyRecruitmentController controller;

    private static final Long USER_ID = 100L;

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
    @DisplayName("myActiveParticipations は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void myActiveParticipations_boundToCurrentUserOnly() {
        controller.myActiveParticipations();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(listingService).getMyListings(USER_ID);
    }

    @Test
    @DisplayName("myFeed は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void myFeed_boundToCurrentUserOnly() {
        controller.myFeed();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(listingService).getMyFeed(USER_ID);
    }
}
