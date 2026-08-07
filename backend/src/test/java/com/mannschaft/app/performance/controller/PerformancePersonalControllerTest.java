package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.MyPerformanceResponse;
import com.mannschaft.app.performance.service.PerformanceMetricService;
import com.mannschaft.app.performance.service.PerformanceStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PerformancePersonalController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>{@code PerformanceStatsService#getMyPerformance} には常に認証主体の {@code USER_ID} のみが渡り、
 * 全チーム横断であっても他ユーザーの成績には到達できないことを固定する。
 * {@code PerformancePersonalController#getMyPerformance} の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformancePersonalController 単体テスト")
class PerformancePersonalControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private PerformanceStatsService statsService;

    @Mock
    private PerformanceMetricService metricService;

    @InjectMocks
    private PerformancePersonalController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getMyPerformance: 認証主体自身の userId のみで全チーム横断に検索する"
            + "（PerformancePersonalController#getMyPerformance）")
    void getMyPerformance_自己スコープ() {
        MyPerformanceResponse res = new MyPerformanceResponse(5L, "テストチーム", List.of());
        given(statsService.getMyPerformance(eq(USER_ID), isNull(), isNull(), isNull()))
                .willReturn(List.of(res));

        ApiResponse<List<MyPerformanceResponse>> result =
                controller.getMyPerformance(null, null, null, null, 20).getBody();

        assertThat(result).isNotNull();
        assertThat(result.getData()).hasSize(1);
        verify(statsService).getMyPerformance(USER_ID, null, null, null);
    }
}
