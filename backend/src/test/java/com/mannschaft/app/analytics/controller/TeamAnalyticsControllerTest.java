package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.TeamOrgAnalyticsErrorCode;
import com.mannschaft.app.analytics.service.PageViewAnalyticsAccessGuard;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.AnalyticsResult;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.DailyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.MonthlyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.SummaryStat;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamAnalyticsController} の API 契約テスト。
 *
 * <p>テスト対象 AC:</p>
 * <ul>
 *   <li>AC-08: 200 + 全フィールド非 null + topContent = []</li>
 *   <li>AC-09: 非メンバー（Guard が 404 投げる）→ 404</li>
 *   <li>AC-11: dateFrom > dateTo → 400</li>
 *   <li>AC-12: 日付省略で正常 200</li>
 *   <li>AC-15: topContent 常に空配列</li>
 *   <li>AC-16: daily[].date = "YYYY-MM-DD"、monthly[].month = "YYYY-MM" 形式</li>
 * </ul>
 */
@WebMvcTest(TeamAnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class TeamAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamService teamService;
    @MockitoBean
    private PageViewAnalyticsAccessGuard accessGuard;
    @MockitoBean
    private PageViewAnalyticsService analyticsService;

    // @WebMvcTest コンテキスト共通の依存解決用
    // SecurityConfig が組み込む JwtAuthenticationFilter は AuthTokenService を要求するため、
    // 既存の動く @WebMvcTest（PublicEventControllerTest / PublicFaqControllerTest）と同様に mock 供給する。
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuardBean;

    private static final Long TEAM_ID = 1L;
    private static final String SLUG = "my-team";

    /** 正常系用のモック AnalyticsResult を構築するヘルパー。 */
    private AnalyticsResult mockResult() {
        SummaryStat summary = new SummaryStat(100L, 50L, 80L, 20L);
        List<DailyStat> daily = List.of(
                new DailyStat(LocalDate.of(2026, 7, 1), 30L, 20L),
                new DailyStat(LocalDate.of(2026, 7, 2), 70L, 40L)
        );
        List<MonthlyStat> monthly = List.of(
                new MonthlyStat(YearMonth.of(2026, 7), 100L, 50L)
        );
        return new AnalyticsResult(summary, daily, monthly);
    }

    // ─── AC-08: 200 + 全フィールド非 null ─────────────────────────────

    @Test
    @DisplayName("AC-08: メンバーが GET すると 200 + 全フィールド非 null")
    void getAnalytics_member_returns200WithAllFields() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        // accessGuard.requireScopeMember は void → 正常系では何もしない（デフォルト）
        given(analyticsService.getAnalytics(eq(PageViewScopeType.TEAM), eq(TEAM_ID), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").exists())
                .andExpect(jsonPath("$.data.summary.totalViews").value(100))
                .andExpect(jsonPath("$.data.summary.uniqueVisitors").value(50))
                .andExpect(jsonPath("$.data.summary.memberViews").value(80))
                .andExpect(jsonPath("$.data.summary.guestViews").value(20))
                .andExpect(jsonPath("$.data.daily").exists())
                .andExpect(jsonPath("$.data.monthly").exists())
                .andExpect(jsonPath("$.data.topContent").exists());
    }

    // ─── AC-09: 非メンバー → 404 ─────────────────────────────────────

    @Test
    @DisplayName("AC-09: 非メンバーが GET すると 404（存在秘匿）")
    void getAnalytics_nonMember_returns404() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        doThrow(new BusinessException(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001))
                .when(accessGuard).requireScopeMember(any(), eq(PageViewScopeType.TEAM), eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAMANALYTICS_001"));
    }

    // ─── AC-11: dateFrom > dateTo → 400 ──────────────────────────────

    @Test
    @DisplayName("AC-11: dateFrom > dateTo なら 400")
    void getAnalytics_invalidDateRange_returns400() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG)
                        .param("dateFrom", "2026-07-10")
                        .param("dateTo", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAMANALYTICS_002"));
    }

    // ─── AC-12: 日付省略で全期間集計（200）────────────────────────────

    @Test
    @DisplayName("AC-12: dateFrom/dateTo 省略で正常 200（全期間集計）")
    void getAnalytics_noDateParams_returns200() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        given(analyticsService.getAnalytics(eq(PageViewScopeType.TEAM), eq(TEAM_ID), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG))
                .andExpect(status().isOk());
    }

    // ─── AC-15: topContent 常に空配列 ─────────────────────────────────

    @Test
    @DisplayName("AC-15: topContent は第 1 弾では常に空配列 []")
    void getAnalytics_topContent_isEmptyArray() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        given(analyticsService.getAnalytics(any(), any(), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topContent").isArray())
                .andExpect(jsonPath("$.data.topContent", hasSize(0)));
    }

    // ─── AC-16: 日付フォーマット検証 ────────────────────────────────

    @Test
    @DisplayName("AC-16: daily[].date は YYYY-MM-DD 形式、monthly[].month は YYYY-MM 形式")
    void getAnalytics_dateFormats_areCorrect() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        given(analyticsService.getAnalytics(any(), any(), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/teams/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-07-01"))
                .andExpect(jsonPath("$.data.daily[1].date").value("2026-07-02"))
                .andExpect(jsonPath("$.data.monthly[0].month").value("2026-07"));
    }
}
