package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.service.RecruitmentNoShowService;
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

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentNoShowController} の単体テスト。
 *
 * <p>RecruitmentNoShowController#getMyNoShows の自己スコープ性を固定する契約テスト。
 * {@code RecruitmentNoShowService#getMyHistory} は {@code SecurityUtils.getCurrentUserId()}
 * のみを検索条件に束縛するため、他人の NO_SHOW 履歴へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentNoShowController 単体テスト")
class RecruitmentNoShowControllerTest {

    @Mock
    private RecruitmentNoShowService noShowService;

    @InjectMocks
    private RecruitmentNoShowController controller;

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
    @DisplayName("getMyNoShows は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyNoShows_boundToCurrentUserOnly() {
        given(noShowService.getMyHistory(USER_ID)).willReturn(List.of());

        controller.getMyNoShows();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(noShowService).getMyHistory(USER_ID);
    }
}
