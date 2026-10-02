package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyService;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltySettingService;
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
 * {@link RecruitmentPenaltyController} の単体テスト。
 *
 * <p>RecruitmentPenaltyController#getMyPenalties の自己スコープ性を固定する契約テスト。
 * {@code RecruitmentPenaltyService#getMyPenalties} は {@code SecurityUtils.getCurrentUserId()}
 * のみを検索条件に束縛するため、他人のペナルティ履歴へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentPenaltyController 単体テスト")
class RecruitmentPenaltyControllerTest {

    @Mock
    private RecruitmentPenaltySettingService settingService;

    @Mock
    private RecruitmentPenaltyService penaltyService;

    @InjectMocks
    private RecruitmentPenaltyController controller;

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
    @DisplayName("getMyPenalties は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyPenalties_boundToCurrentUserOnly() {
        given(penaltyService.getMyPenalties(USER_ID)).willReturn(List.of());

        controller.getMyPenalties();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(penaltyService).getMyPenalties(USER_ID);
    }
}
