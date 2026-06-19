package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.dto.AdminBudgetSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminBusinessAlertScopeResponse;
import com.mannschaft.app.dashboard.dto.AdminMemberStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminPaymentSummaryResponse;
import com.mannschaft.app.dashboard.dto.AdminReportStatsResponse;
import com.mannschaft.app.dashboard.dto.AdminReservationSummaryResponse;
import com.mannschaft.app.dashboard.service.AdminLensSummaryFacade;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F10.1.1 / P3b Wave1: L1 管理者レンズの軽量サマリ集約コントローラ。
 *
 * <p>P1 の {@link AdminActionRequiredController} と同じ {@code /api/v1/dashboard/{team|organization}/{slug}/}
 * 名前空間に揃え、管理者レンズの 3 ウィジェット（業務アラート ⑤ / 通報 ⑥ / 支払 ⑤(org)）向けサマリを返す。
 * 深い操作・一覧は L2/L3 の既存 CRUD へ遷移する（本 API は読み取り専用・設計書 02 §5）。</p>
 *
 * <p><b>認可</b>: 入口で {@link AdminLensSummaryFacade} が {@code checkAdminOrAbove} を必ず通す
 * （ADMIN/DEPUTY のみ・他テナント scopeId は非所属判定で 403・設計書 04 §2 / §5）。
 * パスの {@code slug} は {@code resolveTeamId}/{@code resolveOrgId} で内部 ID に解決する。</p>
 *
 * <p>GET 読み取りのため監査ログ対象外（設計書 04 §7）。</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード（管理者レンズサマリ）")
@RequiredArgsConstructor
public class AdminLensSummaryController {

    private final AdminLensSummaryFacade adminLensSummaryFacade;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 組織パネル ⑤ {@code ADMIN_ORG_PAYMENTS} の支払サマリ（未収 / 期限超過）を取得する。
     */
    @GetMapping("/organization/{orgSlug}/admin-payment-summary")
    @Operation(summary = "組織 支払サマリ（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。組織が発行した未収請求件数（SENT/VIEWED/OVERDUE）と期限超過件数（OVERDUE 単体）を返す")
    public ResponseEntity<ApiResponse<AdminPaymentSummaryResponse>> getOrgPaymentSummary(
            @PathVariable String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getOrgPaymentSummary(userId, orgId)));
    }

    /**
     * チームパネル ⑤ {@code ADMIN_TEAM_ALERT} の業務アラート（新規予約 + 未読問い合わせ）を取得する。
     */
    @GetMapping("/team/{teamSlug}/admin-business-alert")
    @Operation(summary = "チーム 業務アラート（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。本日の新規予約件数と未読問い合わせ件数を返す。承認待ち件数は admin-action-required に一本化")
    public ResponseEntity<ApiResponse<AdminBusinessAlertScopeResponse>> getTeamBusinessAlert(
            @PathVariable String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getTeamBusinessAlert(userId, teamId)));
    }

    /**
     * 組織パネル ⑤ {@code ADMIN_ORG_ALERT} の業務アラート（未読問い合わせのみ）を取得する。
     */
    @GetMapping("/organization/{orgSlug}/admin-business-alert")
    @Operation(summary = "組織 業務アラート（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。未読問い合わせ件数を返す（組織には予約 API が無いため new_reservations は常に 0）")
    public ResponseEntity<ApiResponse<AdminBusinessAlertScopeResponse>> getOrgBusinessAlert(
            @PathVariable String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getOrgBusinessAlert(userId, orgId)));
    }

    /**
     * チームパネル ⑥ {@code ADMIN_TEAM_REPORTS} の通報 stats（未対応 / 確認中）を取得する。
     */
    @GetMapping("/team/{teamSlug}/admin-report-stats")
    @Operation(summary = "チーム 通報サマリ（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。当該チームの未対応(PENDING)/確認中(REVIEWING)通報件数を返す")
    public ResponseEntity<ApiResponse<AdminReportStatsResponse>> getTeamReportStats(
            @PathVariable String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getReportStats(userId, "TEAM", teamId)));
    }

    /**
     * 組織パネル ⑥ {@code ADMIN_ORG_REPORTS} の通報 stats（未対応 / 確認中）を取得する。
     */
    @GetMapping("/organization/{orgSlug}/admin-report-stats")
    @Operation(summary = "組織 通報サマリ（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。当該組織の未対応(PENDING)/確認中(REVIEWING)通報件数を返す")
    public ResponseEntity<ApiResponse<AdminReportStatsResponse>> getOrgReportStats(
            @PathVariable String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getReportStats(userId, "ORGANIZATION", orgId)));
    }

    // ── P3b Wave2: メンバー統計 / 予約サマリ ──────────────────────────

    /**
     * チームパネル ④ {@code ADMIN_TEAM_MEMBERS} のメンバー統計（総数 / アクティブ / 今月新規）を取得する。
     */
    @GetMapping("/team/{teamSlug}/admin-member-stats")
    @Operation(summary = "チーム メンバー統計（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。memberships（在籍）由来の会員総数・アクティブ・今月新規を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "メンバー統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "非 ADMIN/DEPUTY または他テナント（COMMON_002）")
    public ResponseEntity<ApiResponse<AdminMemberStatsResponse>> getTeamMemberStats(
            @PathVariable String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getTeamMemberStats(userId, teamId)));
    }

    /**
     * 組織パネル ④ {@code ADMIN_ORG_MEMBERS} のメンバー統計（総数 / アクティブ / 今月新規）を取得する。
     */
    @GetMapping("/organization/{orgSlug}/admin-member-stats")
    @Operation(summary = "組織 メンバー統計（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。memberships（在籍）由来の会員総数・アクティブ・今月新規を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "メンバー統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "非 ADMIN/DEPUTY または他テナント（COMMON_002）")
    public ResponseEntity<ApiResponse<AdminMemberStatsResponse>> getOrgMemberStats(
            @PathVariable String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getOrgMemberStats(userId, orgId)));
    }

    /**
     * チームパネル ① {@code ADMIN_TEAM_RESERVATIONS} の予約サマリ（承認待ち件数 / 本日の予約数）を取得する。
     */
    @GetMapping("/team/{teamSlug}/admin-reservation-summary")
    @Operation(summary = "チーム 予約サマリ（管理者レンズ）",
            description = "ADMIN/DEPUTY 向け。承認待ち(PENDING)件数と本日(JST)の有効予約(CONFIRMED/PENDING)件数を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "予約サマリ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "非 ADMIN/DEPUTY または他テナント（COMMON_002）")
    public ResponseEntity<ApiResponse<AdminReservationSummaryResponse>> getTeamReservationSummary(
            @PathVariable String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getTeamReservationSummary(userId, teamId)));
    }

    // ── P3b Wave3: 予算サマリ（team / org） ──────────────────────────

    /**
     * チームパネル {@code ADMIN_TEAM_BUDGET} の予算サマリ（配分 / 実績 / 残 / 超過カテゴリ数）を取得する。
     */
    @GetMapping("/team/{teamSlug}/admin-budget-summary")
    @Operation(summary = "チーム 予算サマリ（管理者レンズ）",
            description = "ADMIN または TEAM_BUDGET_VIEW 保有 DEPUTY 向け。現年度の 配分/実績/残/超過カテゴリ数 を返す。現年度未設定時は has_current_fiscal_year=false")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "予算サマリ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "非 ADMIN かつ TEAM_BUDGET_VIEW 未保有、または他テナント（COMMON_002）")
    public ResponseEntity<ApiResponse<AdminBudgetSummaryResponse>> getTeamBudgetSummary(
            @PathVariable String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getTeamBudgetSummary(userId, teamId)));
    }

    /**
     * 組織パネル {@code ADMIN_ORG_BUDGET} の予算サマリ（配分 / 実績 / 残 / 超過カテゴリ数）を取得する。
     */
    @GetMapping("/organization/{orgSlug}/admin-budget-summary")
    @Operation(summary = "組織 予算サマリ（管理者レンズ）",
            description = "ADMIN または BUDGET_VIEW 保有 DEPUTY 向け。現年度の 配分/実績/残/超過カテゴリ数 を返す。現年度未設定時は has_current_fiscal_year=false")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "予算サマリ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "非 ADMIN かつ BUDGET_VIEW 未保有、または他テナント（COMMON_002）")
    public ResponseEntity<ApiResponse<AdminBudgetSummaryResponse>> getOrgBudgetSummary(
            @PathVariable String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        return ResponseEntity.ok(ApiResponse.of(
                adminLensSummaryFacade.getOrgBudgetSummary(userId, orgId)));
    }
}
