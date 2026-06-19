package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.service.UserActiveCountQueryService;
import com.mannschaft.app.chat.service.InquiryAlertQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.AdminBusinessAlertScopeResponse;
import com.mannschaft.app.dashboard.dto.AdminMemberStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminPaymentSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminReportStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminReservationSummaryResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipStatsQueryService;
import com.mannschaft.app.membership.service.MembershipStatsQueryService.MemberStats;
import com.mannschaft.app.moderation.service.ReportScopeStatsQueryService;
import com.mannschaft.app.moderation.service.ReportScopeStatsQueryService.ScopeReportStats;
import com.mannschaft.app.payment.service.PaymentAdminQueryService.OrgPaymentSummary;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminAlertQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService.TeamReservationSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F10.1.1 / P3b: {@link AdminLensSummaryFacade} 単体テスト。
 *
 * <p>観点:</p>
 * <ul>
 *   <li>入口で {@code checkAdminOrAbove} を必ず通す（認可違反は伝播し集計サービスは呼ばれない＝403）</li>
 *   <li>正常時は各ドメイン Query Service へ scope を渡して集約する</li>
 *   <li>組織業務アラートは予約を集計しない（new_reservations=0・予約 API が組織に無い）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminLensSummaryFacade 単体テスト")
class AdminLensSummaryFacadeTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private PaymentAdminQueryService paymentAdminQueryService;
    @Mock
    private ReservationAdminAlertQueryService reservationAdminAlertQueryService;
    @Mock
    private InquiryAlertQueryService inquiryAlertQueryService;
    @Mock
    private ReportScopeStatsQueryService reportScopeStatsQueryService;
    @Mock
    private MembershipStatsQueryService membershipStatsQueryService;
    @Mock
    private UserActiveCountQueryService userActiveCountQueryService;
    @Mock
    private ReservationAdminQueryService reservationAdminQueryService;

    @InjectMocks
    private AdminLensSummaryFacade facade;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    // ── 支払サマリ（org） ──────────────────────────────────────────

    @Test
    @DisplayName("getOrgPaymentSummary → 認可後に summaryForOrg を集約")
    void orgPaymentSummary() {
        given(paymentAdminQueryService.summaryForOrg(ORG_ID))
                .willReturn(new OrgPaymentSummary(8L, 3L));

        AdminPaymentSummaryResponse result = facade.getOrgPaymentSummary(USER_ID, ORG_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        assertThat(result.unsettledCount()).isEqualTo(8);
        assertThat(result.overdueCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getOrgPaymentSummary → 非ADMIN/他org IDOR は 403 で伝播し集計しない")
    void orgPaymentSummaryForbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> facade.getOrgPaymentSummary(USER_ID, ORG_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(paymentAdminQueryService);
    }

    // ── 業務アラート（team / org） ────────────────────────────────

    @Test
    @DisplayName("getTeamBusinessAlert → 認可後に 新規予約 + 未読問い合わせ を集約")
    void teamBusinessAlert() {
        given(reservationAdminAlertQueryService.newReservationsForTeam(TEAM_ID)).willReturn(4L);
        given(inquiryAlertQueryService.unreadInquiriesForTeam(USER_ID, TEAM_ID)).willReturn(7L);

        AdminBusinessAlertScopeResponse result = facade.getTeamBusinessAlert(USER_ID, TEAM_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        assertThat(result.newReservations()).isEqualTo(4L);
        assertThat(result.unreadInquiries()).isEqualTo(7L);
    }

    @Test
    @DisplayName("getOrgBusinessAlert → 組織は予約を集計せず（new_reservations=0）未読問い合わせのみ")
    void orgBusinessAlert() {
        given(inquiryAlertQueryService.unreadInquiriesForOrg(USER_ID, ORG_ID)).willReturn(2L);

        AdminBusinessAlertScopeResponse result = facade.getOrgBusinessAlert(USER_ID, ORG_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        assertThat(result.newReservations()).isZero();
        assertThat(result.unreadInquiries()).isEqualTo(2L);
        // 組織には予約 API が無いため予約集計は呼ばない
        verify(reservationAdminAlertQueryService, never()).newReservationsForTeam(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("getTeamBusinessAlert → 非ADMIN/他team IDOR は 403 で伝播し集計しない")
    void teamBusinessAlertForbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> facade.getTeamBusinessAlert(USER_ID, TEAM_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(reservationAdminAlertQueryService);
        verifyNoInteractions(inquiryAlertQueryService);
    }

    // ── 通報 stats（team / org） ──────────────────────────────────

    @Test
    @DisplayName("getReportStats(TEAM) → 認可後に scope 絞り stats を集約")
    void teamReportStats() {
        given(reportScopeStatsQueryService.scopeStats("TEAM", TEAM_ID))
                .willReturn(new ScopeReportStats(5L, 2L));

        AdminReportStatsResponse result = facade.getReportStats(USER_ID, "TEAM", TEAM_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        assertThat(result.pendingCount()).isEqualTo(5);
        assertThat(result.reviewingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getReportStats → 非ADMIN/他scope IDOR は 403 で伝播し集計しない")
    void reportStatsForbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> facade.getReportStats(USER_ID, "ORGANIZATION", ORG_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(reportScopeStatsQueryService);
    }

    // ── メンバー統計（team / org）P3b Wave2 ──────────────────────────

    @Test
    @DisplayName("getTeamMemberStats → 認可後に membership 統計を集約し active は user ドメインへ委譲（管理者も総数に含む）")
    void teamMemberStats() {
        // 在籍者集合に管理者（USER_ID=1）も含まれている前提（管理者も memberships に MEMBER 行を持つ）。
        given(membershipStatsQueryService.statsForScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(new MemberStats(12L, 3L, java.util.List.of(1L, 2L, 3L)));
        given(userActiveCountQueryService.countActive(java.util.List.of(1L, 2L, 3L))).willReturn(2L);

        AdminMemberStatsResponse result = facade.getTeamMemberStats(USER_ID, TEAM_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        // 総数は管理者を含む全在籍者（12）
        assertThat(result.totalCount()).isEqualTo(12L);
        // アクティブは user ドメイン（status=ACTIVE）の判定結果
        assertThat(result.activeCount()).isEqualTo(2L);
        // 今月新規は joined_at ベースの集計値（昇格者は含めない＝membership 側で保証）
        assertThat(result.newThisMonthCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("getOrgMemberStats → ORGANIZATION スコープで membership 統計を集約")
    void orgMemberStats() {
        given(membershipStatsQueryService.statsForScope(ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(new MemberStats(5L, 1L, java.util.List.of(7L, 8L)));
        given(userActiveCountQueryService.countActive(java.util.List.of(7L, 8L))).willReturn(2L);

        AdminMemberStatsResponse result = facade.getOrgMemberStats(USER_ID, ORG_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        assertThat(result.totalCount()).isEqualTo(5L);
        assertThat(result.activeCount()).isEqualTo(2L);
        assertThat(result.newThisMonthCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getTeamMemberStats → 非ADMIN/別team IDOR は 403 で伝播し集計しない")
    void teamMemberStatsForbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> facade.getTeamMemberStats(USER_ID, TEAM_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(membershipStatsQueryService);
        verifyNoInteractions(userActiveCountQueryService);
    }

    // ── 予約サマリ（team）P3b Wave2 ──────────────────────────────────

    @Test
    @DisplayName("getTeamReservationSummary → 認可後に予約サマリ（承認待ち/本日）を集約")
    void teamReservationSummary() {
        given(reservationAdminQueryService.summaryForTeam(TEAM_ID))
                .willReturn(new TeamReservationSummary(6L, 9L));

        AdminReservationSummaryResponse result = facade.getTeamReservationSummary(USER_ID, TEAM_ID);

        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        assertThat(result.pendingCount()).isEqualTo(6L);
        assertThat(result.todayCount()).isEqualTo(9L);
    }

    @Test
    @DisplayName("getTeamReservationSummary → 非ADMIN/別team IDOR は 403 で伝播し集計しない")
    void teamReservationSummaryForbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> facade.getTeamReservationSummary(USER_ID, TEAM_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(reservationAdminQueryService);
    }
}
