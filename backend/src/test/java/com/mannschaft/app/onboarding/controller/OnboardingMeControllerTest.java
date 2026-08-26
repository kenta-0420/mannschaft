package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.service.OnboardingProgressService;
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
 * {@link OnboardingMeController} の単体テスト。
 *
 * <p>OnboardingMeController#list の自己スコープ性を固定する契約テスト。
 * {@code OnboardingProgressService#listByUser} は {@code SecurityUtils.getCurrentUserId()} のみを
 * 検索条件に束縛するため、他人のオンボーディング進捗へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingMeController 単体テスト")
class OnboardingMeControllerTest {

    @Mock
    private OnboardingProgressService onboardingProgressService;

    @InjectMocks
    private OnboardingMeController controller;

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
    @DisplayName("list は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void list_boundToCurrentUserOnly() {
        controller.list(null);

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(onboardingProgressService).listByUser(USER_ID, null);
    }
}
