package com.mannschaft.app.chart.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.chart.service.ChartRecordService;
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
import org.springframework.data.domain.PageRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * {@link ChartMyController} の単体テスト。
 *
 * <p>ChartMyController#listMyCharts の自己スコープ性を固定する契約テスト。
 * {@code ChartRecordService#listMyCharts} は customerUserId=
 * {@code SecurityUtils.getCurrentUserId()} で絞り込むのみで、teamId は任意の絞り込み条件に
 * すぎず他人のカルテを主体として取得する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChartMyController 単体テスト")
class ChartMyControllerTest {

    @Mock
    private ChartRecordService chartRecordService;

    @InjectMocks
    private ChartMyController controller;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 300L;

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
    @DisplayName("listMyCharts は SecurityUtils.getCurrentUserId() を customerUserId として渡す")
    void listMyCharts_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(chartRecordService.listMyCharts(any(), any(), any()))
            .willReturn(Page.empty());

        controller.listMyCharts(TEAM_ID, 0, 20);

        verify(chartRecordService).listMyCharts(USER_ID, TEAM_ID, PageRequest.of(0, 20));
    }
}
