package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.AdminBudgetSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminBusinessAlertScopeResponse;
import com.mannschaft.app.dashboard.dto.AdminMemberStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminPaymentSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminReportStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminReservationSummaryResponse;
import com.mannschaft.app.dashboard.service.AdminLensSummaryFacade;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P3b: {@link AdminLensSummaryController} 契約テスト（Facade はモック・コントローラ直接呼び出し）。
 *
 * <p>GET 200 形状・認可 403 伝播・IDOR 403 を検証する。{@code SecurityUtils.getCurrentUserId()} は
 * authentication.getName() を userId として読むため "1" を設定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminLensSummaryController 契約テスト")
class AdminLensSummaryControllerTest {

    @Mock
    private AdminLensSummaryFacade adminLensSummaryFacade;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private AdminLensSummaryController controller;

    private static final Long USER_ID = 1L;
    private static final String TEAM_SLUG = "dev-team";
    private static final Long TEAM_ID = 10L;
    private static final String ORG_SLUG = "dev-org";
    private static final Long ORG_ID = 20L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-payment-summary: 200・未収/期限超過")
    void orgPaymentSummary200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminLensSummaryFacade.getOrgPaymentSummary(USER_ID, ORG_ID))
                .willReturn(AdminPaymentSummaryResponse.builder().unsettledCount(8).overdueCount(3).build());

        ResponseEntity<ApiResponse<AdminPaymentSummaryResponse>> res =
                controller.getOrgPaymentSummary(ORG_SLUG);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().unsettledCount()).isEqualTo(8);
        assertThat(res.getBody().getData().overdueCount()).isEqualTo(3);
        verify(adminLensSummaryFacade).getOrgPaymentSummary(USER_ID, ORG_ID);
    }

    @Test
    @DisplayName("GET team/{slug}/admin-business-alert: 200・新規予約/未読問い合わせ")
    void teamBusinessAlert200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getTeamBusinessAlert(USER_ID, TEAM_ID))
                .willReturn(AdminBusinessAlertScopeResponse.builder().newReservations(4).unreadInquiries(7).build());

        ResponseEntity<ApiResponse<AdminBusinessAlertScopeResponse>> res =
                controller.getTeamBusinessAlert(TEAM_SLUG);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().newReservations()).isEqualTo(4);
        assertThat(res.getBody().getData().unreadInquiries()).isEqualTo(7);
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-business-alert: 200・未読問い合わせのみ（new_reservations=0）")
    void orgBusinessAlert200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminLensSummaryFacade.getOrgBusinessAlert(USER_ID, ORG_ID))
                .willReturn(AdminBusinessAlertScopeResponse.builder().newReservations(0).unreadInquiries(2).build());

        ResponseEntity<ApiResponse<AdminBusinessAlertScopeResponse>> res =
                controller.getOrgBusinessAlert(ORG_SLUG);

        assertThat(res.getBody().getData().newReservations()).isZero();
        assertThat(res.getBody().getData().unreadInquiries()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET team/{slug}/admin-report-stats: 200・未対応/確認中")
    void teamReportStats200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getReportStats(USER_ID, "TEAM", TEAM_ID))
                .willReturn(AdminReportStatsResponse.builder().pendingCount(5).reviewingCount(2).build());

        ResponseEntity<ApiResponse<AdminReportStatsResponse>> res =
                controller.getTeamReportStats(TEAM_SLUG);

        assertThat(res.getBody().getData().pendingCount()).isEqualTo(5);
        assertThat(res.getBody().getData().reviewingCount()).isEqualTo(2);
        verify(adminLensSummaryFacade).getReportStats(USER_ID, "TEAM", TEAM_ID);
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-report-stats: 200")
    void orgReportStats200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminLensSummaryFacade.getReportStats(USER_ID, "ORGANIZATION", ORG_ID))
                .willReturn(AdminReportStatsResponse.builder().pendingCount(1).reviewingCount(0).build());

        ResponseEntity<ApiResponse<AdminReportStatsResponse>> res =
                controller.getOrgReportStats(ORG_SLUG);

        assertThat(res.getBody().getData().pendingCount()).isEqualTo(1);
        verify(adminLensSummaryFacade).getReportStats(USER_ID, "ORGANIZATION", ORG_ID);
    }

    // ── P3b Wave2: メンバー統計 / 予約サマリ ──────────────────────────

    @Test
    @DisplayName("GET team/{slug}/admin-member-stats: 200・総数/アクティブ/今月新規")
    void teamMemberStats200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getTeamMemberStats(USER_ID, TEAM_ID))
                .willReturn(AdminMemberStatsResponse.builder()
                        .totalCount(12).activeCount(10).newThisMonthCount(3).build());

        ResponseEntity<ApiResponse<AdminMemberStatsResponse>> res =
                controller.getTeamMemberStats(TEAM_SLUG);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().totalCount()).isEqualTo(12);
        assertThat(res.getBody().getData().activeCount()).isEqualTo(10);
        assertThat(res.getBody().getData().newThisMonthCount()).isEqualTo(3);
        verify(adminLensSummaryFacade).getTeamMemberStats(USER_ID, TEAM_ID);
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-member-stats: 200")
    void orgMemberStats200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminLensSummaryFacade.getOrgMemberStats(USER_ID, ORG_ID))
                .willReturn(AdminMemberStatsResponse.builder()
                        .totalCount(5).activeCount(5).newThisMonthCount(1).build());

        ResponseEntity<ApiResponse<AdminMemberStatsResponse>> res =
                controller.getOrgMemberStats(ORG_SLUG);

        assertThat(res.getBody().getData().totalCount()).isEqualTo(5);
        verify(adminLensSummaryFacade).getOrgMemberStats(USER_ID, ORG_ID);
    }

    @Test
    @DisplayName("GET team/{slug}/admin-reservation-summary: 200・承認待ち/本日")
    void teamReservationSummary200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getTeamReservationSummary(USER_ID, TEAM_ID))
                .willReturn(AdminReservationSummaryResponse.builder()
                        .pendingCount(6).todayCount(9).build());

        ResponseEntity<ApiResponse<AdminReservationSummaryResponse>> res =
                controller.getTeamReservationSummary(TEAM_SLUG);

        assertThat(res.getBody().getData().pendingCount()).isEqualTo(6);
        assertThat(res.getBody().getData().todayCount()).isEqualTo(9);
        verify(adminLensSummaryFacade).getTeamReservationSummary(USER_ID, TEAM_ID);
    }

    // ── P3b Wave3: 予算サマリ ──────────────────────────

    @Test
    @DisplayName("GET team/{slug}/admin-budget-summary: 200・配分/実績/残/超過カテゴリ数")
    void teamBudgetSummary200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getTeamBudgetSummary(USER_ID, TEAM_ID))
                .willReturn(AdminBudgetSummaryResponse.builder()
                        .hasCurrentFiscalYear(true).fiscalYearName("2026年度")
                        .allocation(new BigDecimal("1500")).actual(new BigDecimal("1400"))
                        .remaining(new BigDecimal("100")).overBudgetCategoryCount(1L).build());

        ResponseEntity<ApiResponse<AdminBudgetSummaryResponse>> res =
                controller.getTeamBudgetSummary(TEAM_SLUG);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().hasCurrentFiscalYear()).isTrue();
        assertThat(res.getBody().getData().allocation()).isEqualByComparingTo("1500");
        assertThat(res.getBody().getData().overBudgetCategoryCount()).isEqualTo(1L);
        verify(adminLensSummaryFacade).getTeamBudgetSummary(USER_ID, TEAM_ID);
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-budget-summary: 200")
    void orgBudgetSummary200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminLensSummaryFacade.getOrgBudgetSummary(USER_ID, ORG_ID))
                .willReturn(AdminBudgetSummaryResponse.builder()
                        .hasCurrentFiscalYear(true).fiscalYearName("2026年度")
                        .allocation(new BigDecimal("3000")).actual(new BigDecimal("1000"))
                        .remaining(new BigDecimal("2000")).overBudgetCategoryCount(0L).build());

        ResponseEntity<ApiResponse<AdminBudgetSummaryResponse>> res =
                controller.getOrgBudgetSummary(ORG_SLUG);

        assertThat(res.getBody().getData().allocation()).isEqualByComparingTo("3000");
        assertThat(res.getBody().getData().overBudgetCategoryCount()).isZero();
        verify(adminLensSummaryFacade).getOrgBudgetSummary(USER_ID, ORG_ID);
    }

    @Test
    @DisplayName("GET team/{slug}/admin-budget-summary: 現年度未設定 → has_current_fiscal_year=false")
    void teamBudgetSummary_noFiscalYear() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminLensSummaryFacade.getTeamBudgetSummary(USER_ID, TEAM_ID))
                .willReturn(AdminBudgetSummaryResponse.builder()
                        .hasCurrentFiscalYear(false).fiscalYearName(null)
                        .allocation(BigDecimal.ZERO).actual(BigDecimal.ZERO)
                        .remaining(BigDecimal.ZERO).overBudgetCategoryCount(0L).build());

        ResponseEntity<ApiResponse<AdminBudgetSummaryResponse>> res =
                controller.getTeamBudgetSummary(TEAM_SLUG);

        assertThat(res.getBody().getData().hasCurrentFiscalYear()).isFalse();
        assertThat(res.getBody().getData().fiscalYearName()).isNull();
    }

    @Test
    @DisplayName("team admin-budget-summary: 権限なし DEPUTY（403）→ Facade の COMMON_002 が伝播")
    void teamBudgetSummaryForbidden403() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(adminLensSummaryFacade).getTeamBudgetSummary(USER_ID, TEAM_ID);

        assertThatThrownBy(() -> controller.getTeamBudgetSummary(TEAM_SLUG))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("非ADMIN/他テナント（IDOR）→ Facade の checkAdminOrAbove が COMMON_002 を投げ 403 伝播")
    void forbiddenPropagates403() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(adminLensSummaryFacade).getTeamBusinessAlert(USER_ID, TEAM_ID);

        assertThatThrownBy(() -> controller.getTeamBusinessAlert(TEAM_SLUG))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
    }
}
