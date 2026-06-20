package com.mannschaft.app.activity;

import com.mannschaft.app.activity.controller.ActivityStatsController;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

/**
 * 活動統計コントローラーの認可ゲート契約テスト（AC-8）。
 *
 * <p>統計・エクスポートは Controller 層で {@code AccessControlService.checkMembership} を先行実行し、
 * 非会員には 403（COMMON_002）を返す。サービスへは委譲されないことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityStatsController 認可ゲート契約テスト")
class ActivityStatsControllerAuthzTest {

    @Mock private ActivityStatsService statsService;
    @Mock private AccessControlService accessControlService;

    private ActivityStatsController controller;
    private MockedStatic<SecurityUtils> securityUtils;

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 7L;

    @BeforeEach
    void setUp() {
        controller = new ActivityStatsController(statsService, accessControlService);
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // AC-8: 統計取得は他スコープ会員で403、サービス非委譲
    @Test
    @DisplayName("getStats_他スコープ会員は403（COMMON_002）でサービス非委譲")
    void 統計_他スコープ_403() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> controller.getStats("TEAM", SCOPE_ID, null, "MONTH", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("COMMON_002"));
        verify(statsService, never()).getStats(any(), any(), any(), any(), any(), any());
    }

    // AC-8: フィールド集計も同様に403、サービス非委譲
    @Test
    @DisplayName("getFieldStats_他スコープ会員は403（COMMON_002）でサービス非委譲")
    void フィールド集計_他スコープ_403() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> controller.getFieldStats("TEAM", SCOPE_ID, 1L, "key", "MONTH"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("COMMON_002"));
        verify(statsService, never()).getFieldStats(any(), any(), any(), any(), any());
    }

    // AC-8: CSVエクスポートも403、サービス非委譲
    @Test
    @DisplayName("exportCsv_他スコープ会員は403（COMMON_002）でサービス非委譲")
    void エクスポート_他スコープ_403() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> controller.exportCsv("TEAM", SCOPE_ID, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("COMMON_002"));
        verify(statsService, never()).exportCsv(any(), any(), any(), any(), any(), any());
    }

    // AC-2: 自スコープ会員は従来通り成功（非回帰）。membership検証後にサービスへ委譲される
    @Test
    @DisplayName("getStats_自スコープ会員は成功しサービスへ委譲（非回帰）")
    void 統計_自スコープ_委譲() {
        given(statsService.getStats(any(), any(), any(), any(), any(), any())).willReturn(null);

        controller.getStats("TEAM", SCOPE_ID, null, "MONTH", null, null);

        verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        verify(statsService).getStats(ActivityScopeType.TEAM, SCOPE_ID, null, "MONTH", null, null);
    }
}
