package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.service.UserViolationService;
import com.mannschaft.app.moderation.service.WarningReReviewService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserViolationController} の単体テスト。
 *
 * <p>UserViolationController#getMyViolations の自己スコープ性を固定する契約テスト。
 * {@code violationService.getViolationHistory} は {@code SecurityUtils.getCurrentUserId()} の
 * みを検索条件に束縛するため、他人の違反履歴へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserViolationController 単体テスト")
class UserViolationControllerTest {

    @Mock
    private UserViolationService violationService;

    @Mock
    private WarningReReviewService reReviewService;

    @InjectMocks
    private UserViolationController controller;

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
    @DisplayName("getMyViolations は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyViolations_boundToCurrentUserOnly() {
        UserViolationHistoryResponse response = Mockito.mock(UserViolationHistoryResponse.class);
        given(violationService.getViolationHistory(USER_ID)).willReturn(response);

        ResponseEntity<ApiResponse<UserViolationHistoryResponse>> result = controller.getMyViolations();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(violationService).getViolationHistory(USER_ID);
    }
}
