package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.TeamOrgAnalyticsErrorCode;
import com.mannschaft.app.analytics.service.PageViewAnalyticsAccessGuard;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.AnalyticsResult;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.ContentStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.DailyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.MonthlyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.SummaryStat;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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
 * {@link OrganizationAnalyticsController} の API 契約テスト（AC-17 同型検証）。
 *
 * <p>チーム版（{@link TeamAnalyticsControllerTest}）と同一構造・同一権限規則であることを確認する。</p>
 *
 * <p>テスト対象 AC:</p>
 * <ul>
 *   <li>AC-08: 200 + 全フィールド非 null + topContent = []</li>
 *   <li>AC-09: 非メンバー → 404</li>
 *   <li>AC-11: dateFrom > dateTo → 400</li>
 *   <li>AC-12: 日付省略で正常 200</li>
 *   <li>AC-P2-8: topContent 実データ配列（第 2 弾）</li>
 *   <li>AC-16: 日付フォーマット</li>
 *   <li>AC-17: 組織版がチーム版と同一構造で動作する</li>
 * </ul>
 */
@WebMvcTest(OrganizationAnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrganizationAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;
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

    private static final Long ORG_ID = 2L;
    private static final String SLUG = "my-org";

    /** 正常系用のモック AnalyticsResult を構築するヘルパー。 */
    private AnalyticsResult mockResult() {
        SummaryStat summary = new SummaryStat(200L, 100L, 150L, 50L);
        List<DailyStat> daily = List.of(
                new DailyStat(LocalDate.of(2026, 7, 1), 60L, 40L),
                new DailyStat(LocalDate.of(2026, 7, 2), 140L, 80L)
        );
        List<MonthlyStat> monthly = List.of(
                new MonthlyStat(YearMonth.of(2026, 7), 200L, 100L)
        );
        List<ContentStat> topContent = List.of(
                new ContentStat("PAGE", 0L, "組織トップ", "/organizations/my-org", 120L, 70L),
                new ContentStat("ARTICLE", 3L, "組織のお知らせ", "/organizations/my-org/articles/3", 30L, 20L)
        );
        return new AnalyticsResult(summary, daily, monthly, topContent);
    }

    // ─── AC-08/AC-17: 200 + 全フィールド非 null ───────────────────────

    @Test
    @DisplayName("AC-08/AC-17: 組織メンバーが GET すると 200 + 全フィールド非 null")
    void getAnalytics_member_returns200WithAllFields() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(analyticsService.getAnalytics(eq(PageViewScopeType.ORGANIZATION), eq(ORG_ID), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").exists())
                .andExpect(jsonPath("$.data.summary.totalViews").value(200))
                .andExpect(jsonPath("$.data.summary.uniqueVisitors").value(100))
                .andExpect(jsonPath("$.data.summary.memberViews").value(150))
                .andExpect(jsonPath("$.data.summary.guestViews").value(50))
                .andExpect(jsonPath("$.data.daily").exists())
                .andExpect(jsonPath("$.data.monthly").exists())
                .andExpect(jsonPath("$.data.topContent").exists());
    }

    // ─── AC-09: 非メンバー → 404 ─────────────────────────────────────

    @Test
    @DisplayName("AC-09: 非メンバーが GET すると 404（存在秘匿）")
    void getAnalytics_nonMember_returns404() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        doThrow(new BusinessException(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001))
                .when(accessGuard).requireScopeMember(any(), eq(PageViewScopeType.ORGANIZATION), eq(ORG_ID));

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAMANALYTICS_001"));
    }

    // ─── AC-11: dateFrom > dateTo → 400 ──────────────────────────────

    @Test
    @DisplayName("AC-11: dateFrom > dateTo なら 400（TEAMANALYTICS_002）")
    void getAnalytics_invalidDateRange_returns400() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG)
                        .param("dateFrom", "2026-07-10")
                        .param("dateTo", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAMANALYTICS_002"));
    }

    // ─── AC-12: 日付省略で 200 ───────────────────────────────────────

    @Test
    @DisplayName("AC-12: dateFrom/dateTo 省略で正常 200")
    void getAnalytics_noDateParams_returns200() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(analyticsService.getAnalytics(eq(PageViewScopeType.ORGANIZATION), eq(ORG_ID), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG))
                .andExpect(status().isOk());
    }

    // ─── AC-P2-8: topContent は第 2 弾で実データ配列を返す ───────────────

    @Test
    @DisplayName("AC-P2-8: topContent は第 2 弾では実データ配列（全フィールド）で返る")
    void getAnalytics_topContent_returnsRealData() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(analyticsService.getAnalytics(any(), any(), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topContent").isArray())
                .andExpect(jsonPath("$.data.topContent", hasSize(2)))
                .andExpect(jsonPath("$.data.topContent[0].contentType").value("PAGE"))
                .andExpect(jsonPath("$.data.topContent[0].contentId").value(0))
                .andExpect(jsonPath("$.data.topContent[0].title").value("組織トップ"))
                .andExpect(jsonPath("$.data.topContent[0].url").value("/organizations/my-org"))
                .andExpect(jsonPath("$.data.topContent[0].views").value(120))
                .andExpect(jsonPath("$.data.topContent[0].uniqueVisitors").value(70));
    }

    // ─── AC-16: 日付フォーマット ─────────────────────────────────────

    @Test
    @DisplayName("AC-16: daily[].date は YYYY-MM-DD、monthly[].month は YYYY-MM 形式")
    void getAnalytics_dateFormats_areCorrect() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(analyticsService.getAnalytics(any(), any(), isNull(), isNull()))
                .willReturn(mockResult());

        mockMvc.perform(get("/api/v1/organizations/{slug}/analytics", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-07-01"))
                .andExpect(jsonPath("$.data.daily[1].date").value("2026-07-02"))
                .andExpect(jsonPath("$.data.monthly[0].month").value("2026-07"));
    }
}
