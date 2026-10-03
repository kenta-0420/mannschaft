package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.moderation.dto.CreateUnflagRequest;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.service.YabaiUnflagService;
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
 * {@link YabaiController} の単体テスト。
 *
 * <p>YabaiController#createUnflagRequest / #getUnflagRequestStatus の自己スコープ性を
 * 固定する契約テスト。いずれも {@code SecurityUtils.getCurrentUserId()} のみを
 * サービス呼び出しの主体として渡すため、他人の解除申請へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("YabaiController 単体テスト")
class YabaiControllerTest {

    @Mock
    private YabaiUnflagService unflagService;

    @InjectMocks
    private YabaiController controller;

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
    @DisplayName("createUnflagRequest は SecurityUtils.getCurrentUserId() のみを申請者として渡す")
    void createUnflagRequest_boundToCurrentUserOnly() {
        CreateUnflagRequest request = new CreateUnflagRequest("解除希望");
        YabaiUnflagResponse response = Mockito.mock(YabaiUnflagResponse.class);
        given(unflagService.createUnflagRequest(USER_ID, "解除希望")).willReturn(response);

        ResponseEntity<ApiResponse<YabaiUnflagResponse>> result = controller.createUnflagRequest(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(unflagService).createUnflagRequest(USER_ID, "解除希望");
    }

    @Test
    @DisplayName("getUnflagRequestStatus は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getUnflagRequestStatus_boundToCurrentUserOnly() {
        YabaiUnflagResponse response = Mockito.mock(YabaiUnflagResponse.class);
        given(unflagService.getLatestRequestStatus(USER_ID)).willReturn(response);

        ResponseEntity<ApiResponse<YabaiUnflagResponse>> result = controller.getUnflagRequestStatus();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(unflagService).getLatestRequestStatus(USER_ID);
    }
}
