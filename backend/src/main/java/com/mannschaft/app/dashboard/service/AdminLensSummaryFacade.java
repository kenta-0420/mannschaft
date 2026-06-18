package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.chat.service.InquiryAlertQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.AdminBusinessAlertScopeResponse;
import com.mannschaft.app.dashboard.dto.AdminPaymentSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminReportStatsResponse;
import com.mannschaft.app.moderation.service.ReportScopeStatsQueryService;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminAlertQueryService;
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
}
