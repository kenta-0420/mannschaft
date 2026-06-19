package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.service.UserActiveCountQueryService;
import com.mannschaft.app.budget.service.BudgetAdminSummaryQueryService;
import com.mannschaft.app.chat.service.InquiryAlertQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.AdminBudgetSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminBusinessAlertScopeResponse;
import com.mannschaft.app.dashboard.dto.AdminMemberStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminPaymentSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminReportStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminReservationSummaryResponse;
import com.mannschaft.app.membership.service.MembershipStatsQueryService;
import com.mannschaft.app.moderation.service.ReportScopeStatsQueryService;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminAlertQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * F10.1.1 / P3b Wave1: L1 管理者レンズの軽量サマリ集約ファサード（dashboard ドメイン）。
 *
 * <p>P1 の {@link AdminActionRequiredFacade}（横断「承認待ち」集約）と同じ作法で、管理者レンズの
 * 3 ウィジェット向けサマリを集約する:</p>
 * <ul>
 *   <li>⑤ 業務アラート（{@code ADMIN_TEAM_ALERT}/{@code ADMIN_ORG_ALERT}）= 新規予約 + 未読問い合わせ</li>
 *   <li>⑥ 通報（{@code ADMIN_TEAM_REPORTS}/{@code ADMIN_ORG_REPORTS}）= 未対応 / 確認中 件数</li>
 *   <li>⑤(org) 支払（{@code ADMIN_ORG_PAYMENTS}）= 未収 / 期限超過 件数</li>
 * </ul>
 *
 * <p><b>認可</b>: 各メソッド入口で {@link AccessControlService#checkAdminOrAbove} を必ず通す
 * （ADMIN/DEPUTY のみ・他テナント scopeId は非所属判定で 403・設計書 04 §2 / §5）。認可違反（COMMON_002）は
 * 握りつぶさず伝播させ、集計サービスは呼ばない。</p>
 *
 * <p><b>原則 5 遵守</b>: 読み取り集約のため {@code @Transactional} をドメイン跨ぎに張らない。
 * 各ドメインの {@code @Transactional(readOnly=true)} Query Service を呼び、戻り値をそのまま返すのみ。</p>
 *
 * <p><b>二重計上回避</b>: 業務アラート ⑤ は「新規予約・未読問い合わせ」のみを持ち、承認待ち（pending）は
 * P1 集約 API（{@link AdminActionRequiredFacade} の {@code total_pending}）に一本化する（設計書 02 §3）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2 / §3 / 04_security_authorization.md</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLensSummaryFacade {

    private final AccessControlService accessControlService;
    private final PaymentAdminQueryService paymentAdminQueryService;
    private final ReservationAdminAlertQueryService reservationAdminAlertQueryService;
    private final InquiryAlertQueryService inquiryAlertQueryService;
    private final ReportScopeStatsQueryService reportScopeStatsQueryService;
    private final MembershipStatsQueryService membershipStatsQueryService;
    private final UserActiveCountQueryService userActiveCountQueryService;
    private final ReservationAdminQueryService reservationAdminQueryService;
    private final BudgetAdminSummaryQueryService budgetAdminSummaryQueryService;

    /** TEAM スコープ予算閲覧権限名（V115.001 で seed・DEPUTY が保有しうる）。 */
    private static final String TEAM_BUDGET_VIEW_PERMISSION = "TEAM_BUDGET_VIEW";
    /** ORG スコープ予算閲覧権限名（V11.034 で seed 済み）。 */
    private static final String ORG_BUDGET_VIEW_PERMISSION = "BUDGET_VIEW";

    /**
     * 組織パネル ⑤ {@code ADMIN_ORG_PAYMENTS} のサマリ（未収 / 期限超過）を取得する。
     *
     * @param userId 閲覧ユーザー ID（認可主体・パスの orgId は信用せず本値で判定）
     * @param orgId  組織 ID（slug 解決済み内部 ID）
     */
    public AdminPaymentSummaryResponse getOrgPaymentSummary(Long userId, Long orgId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        PaymentAdminQueryService.OrgPaymentSummary summary = paymentAdminQueryService.summaryForOrg(orgId);
        return AdminPaymentSummaryResponse.builder()
                .unsettledCount(summary.unsettledCount())
                .overdueCount(summary.overdueCount())
                .build();
    }

    /**
     * チームパネル ⑤ {@code ADMIN_TEAM_ALERT} の業務アラート（新規予約 + 未読問い合わせ）を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @param teamId チーム ID（slug 解決済み内部 ID）
     */
    public AdminBusinessAlertScopeResponse getTeamBusinessAlert(Long userId, Long teamId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        long newReservations = reservationAdminAlertQueryService.newReservationsForTeam(teamId);
        long unreadInquiries = inquiryAlertQueryService.unreadInquiriesForTeam(userId, teamId);
        return AdminBusinessAlertScopeResponse.builder()
                .newReservations(newReservations)
                .unreadInquiries(unreadInquiries)
                .build();
    }

    /**
     * 組織パネル ⑤ {@code ADMIN_ORG_ALERT} の業務アラート（未読問い合わせのみ）を取得する。
     *
     * <p>組織スコープには予約 API が無い（{@code ReservationEntity} に organization_id 無し・設計書 02 §2.3）ため、
     * {@code new_reservations} は常に 0 とし、未読問い合わせのみを集計する。</p>
     *
     * @param userId 閲覧ユーザー ID
     * @param orgId  組織 ID（slug 解決済み内部 ID）
     */
    public AdminBusinessAlertScopeResponse getOrgBusinessAlert(Long userId, Long orgId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        long unreadInquiries = inquiryAlertQueryService.unreadInquiriesForOrg(userId, orgId);
        return AdminBusinessAlertScopeResponse.builder()
                .newReservations(0L)
                .unreadInquiries(unreadInquiries)
                .build();
    }

    /**
     * チーム/組織パネル ⑥ {@code ADMIN_*_REPORTS} の通報 stats（未対応 / 確認中）を取得する。
     *
     * @param userId    閲覧ユーザー ID
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID（slug 解決済み内部 ID）
     */
    public AdminReportStatsResponse getReportStats(Long userId, String scopeType, Long scopeId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        ReportScopeStatsQueryService.ScopeReportStats stats =
                reportScopeStatsQueryService.scopeStats(scopeType, scopeId);
        return AdminReportStatsResponse.builder()
                .pendingCount(stats.pendingCount())
                .reviewingCount(stats.reviewingCount())
                .build();
    }

    /**
     * チームパネル ④ {@code ADMIN_TEAM_MEMBERS} のメンバー統計（総数 / アクティブ / 今月新規）を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @param teamId チーム ID（slug 解決済み内部 ID）
     */
    public AdminMemberStatsResponse getTeamMemberStats(Long userId, Long teamId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return buildMemberStats(com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId);
    }

    /**
     * 組織パネル ④ {@code ADMIN_ORG_MEMBERS} のメンバー統計（総数 / アクティブ / 今月新規）を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @param orgId  組織 ID（slug 解決済み内部 ID）
     */
    public AdminMemberStatsResponse getOrgMemberStats(Long userId, Long orgId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        return buildMemberStats(com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgId);
    }

    /**
     * メンバー統計を組み立てる。母集合（総数・今月新規・在籍 user_id 集合）は membership ドメインで集計し、
     * 「アクティブ」（users.status='ACTIVE'）件数のみ user(auth) ドメインへ委ねる（ドメイン境界厳守）。
     */
    private AdminMemberStatsResponse buildMemberStats(
            com.mannschaft.app.membership.domain.ScopeType scopeType, Long scopeId) {
        MembershipStatsQueryService.MemberStats stats =
                membershipStatsQueryService.statsForScope(scopeType, scopeId);
        long activeCount = userActiveCountQueryService.countActive(stats.activeUserIds());
        return AdminMemberStatsResponse.builder()
                .totalCount(stats.totalCount())
                .activeCount(activeCount)
                .newThisMonthCount(stats.newThisMonthCount())
                .build();
    }

    /**
     * チームパネル ① {@code ADMIN_TEAM_RESERVATIONS} の予約サマリ（承認待ち件数 / 本日の予約数）を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @param teamId チーム ID（slug 解決済み内部 ID）
     */
    public AdminReservationSummaryResponse getTeamReservationSummary(Long userId, Long teamId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        ReservationAdminQueryService.TeamReservationSummary summary =
                reservationAdminQueryService.summaryForTeam(teamId);
        return AdminReservationSummaryResponse.builder()
                .pendingCount(summary.pendingCount())
                .todayCount(summary.todayCount())
                .build();
    }

    // ── P3b Wave3: 予算サマリ（team / org） ──────────────────────────

    /**
     * チームパネル {@code ADMIN_TEAM_BUDGET} の予算サマリ（配分 / 実績 / 残 / 超過カテゴリ数）を取得する。
     *
     * <p><b>認可（TEAM 細粒度）</b>: {@code checkAdminOrAbove} では DEPUTY を一律通してしまい
     * 「予算閲覧権限を持つ DEPUTY のみ通す」細粒度に届かない。また {@code checkAdminOrHasPermission} は
     * ORGANIZATION 専用で TEAM に渡すと {@link IllegalArgumentException} を投げる。よって TEAM では
     * {@code isAdmin || hasPermission(TEAM, "TEAM_BUDGET_VIEW")} を明示判定し、満たさなければ 403（COMMON_002）。</p>
     *
     * @param userId 閲覧ユーザー ID（認可主体・パスの teamId は信用せず本値で判定）
     * @param teamId チーム ID（slug 解決済み内部 ID）
     */
    public AdminBudgetSummaryResponse getTeamBudgetSummary(Long userId, Long teamId) {
        boolean allowed = accessControlService.isAdmin(userId, teamId, "TEAM")
                || accessControlService.hasPermission(userId, teamId, "TEAM", TEAM_BUDGET_VIEW_PERMISSION);
        if (!allowed) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return toResponse(budgetAdminSummaryQueryService.summaryForScope("TEAM", teamId));
    }

    /**
     * 組織パネル {@code ADMIN_ORG_BUDGET} の予算サマリ（配分 / 実績 / 残 / 超過カテゴリ数）を取得する。
     *
     * <p><b>認可（ORG 細粒度）</b>: ORGANIZATION 専用の {@code checkAdminOrHasPermission} を用い、
     * 「ADMIN または BUDGET_VIEW 権限を持つ DEPUTY」のみ通す。違反は COMMON_002（403）で伝播し集計しない。</p>
     *
     * @param userId 閲覧ユーザー ID（認可主体・パスの orgId は信用せず本値で判定）
     * @param orgId  組織 ID（slug 解決済み内部 ID）
     */
    public AdminBudgetSummaryResponse getOrgBudgetSummary(Long userId, Long orgId) {
        accessControlService.checkAdminOrHasPermission(userId, orgId, "ORGANIZATION", ORG_BUDGET_VIEW_PERMISSION);
        return toResponse(budgetAdminSummaryQueryService.summaryForScope("ORGANIZATION", orgId));
    }

    /**
     * budget ドメインローカル集計を dashboard DTO へ変換する。
     */
    private AdminBudgetSummaryResponse toResponse(
            BudgetAdminSummaryQueryService.BudgetAdminSummary summary) {
        return AdminBudgetSummaryResponse.builder()
                .hasCurrentFiscalYear(summary.hasCurrentFiscalYear())
                .fiscalYearName(summary.fiscalYearName())
                .allocation(summary.allocation())
                .actual(summary.actual())
                .remaining(summary.remaining())
                .overBudgetCategoryCount(summary.overBudgetCategoryCount())
                .build();
    }
}
