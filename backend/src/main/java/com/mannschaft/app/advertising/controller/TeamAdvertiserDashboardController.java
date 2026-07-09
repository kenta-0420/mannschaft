package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.CampaignPerformanceResponse;
import com.mannschaft.app.advertising.dto.CreditLimitRequestResponse;
import com.mannschaft.app.advertising.dto.InvoiceSummaryResponse;
import com.mannschaft.app.advertising.dto.ReportScheduleResponse;
import com.mannschaft.app.advertising.service.AdCreditLimitRequestService;
import com.mannschaft.app.advertising.service.AdInvoiceService;
import com.mannschaft.app.advertising.service.AdReportScheduleService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.advertising.service.CampaignPerformanceService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * チームスコープ 広告主ダッシュボードコントローラー（F09.19 §6.1 / §16 F09.19.5 AC-5.2）。
 *
 * <p>組織版 {@link AdvertiserDashboardController} の invoices / credit-limit-requests /
 * report-schedules / performance を scope=TEAM で対称提供する。認可は当該チーム ADMIN 以上
 * （{@code AccessControlService.checkAdminOrAbove}）。他チームの ADMIN は越境として 403（COMMON_002）。
 * 応答形式は org 版と同一（invoices は {@link PagedResponse} = data + meta、他は {@link ApiResponse} = data）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser")
@RequiredArgsConstructor
public class TeamAdvertiserDashboardController {

    private final AdvertiserAccountService advertiserAccountService;
    private final AdInvoiceService adInvoiceService;
    private final AdReportScheduleService adReportScheduleService;
    private final AdCreditLimitRequestService adCreditLimitRequestService;
    private final CampaignPerformanceService campaignPerformanceService;
    private final AccessControlService accessControlService;

    /** チームスコープの権限検証。指定チームの ADMIN 以上であることを確認する（越境は 403）。 */
    private void verifyTeamAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
    }

    // ─────────────────────────────────────────────
    // 請求書
    // ─────────────────────────────────────────────

    /** 請求書一覧（org 版と同一の PagedResponse 形式 = data + meta）。 */
    @GetMapping("/invoices")
    public PagedResponse<InvoiceSummaryResponse> listInvoices(
            @PathVariable Long teamId,
            @RequestParam(required = false) InvoiceStatus status,
            Pageable pageable) {
        verifyTeamAccess(teamId);
        AdvertiserAccountResponse account = advertiserAccountService.getByScope(ScopeType.TEAM, teamId);
        Page<InvoiceSummaryResponse> page = adInvoiceService.findByAccountId(account.id(), status, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    // ─────────────────────────────────────────────
    // credit_limit 増額申請
    // ─────────────────────────────────────────────

    /** 増額申請履歴。 */
    @GetMapping("/credit-limit-requests")
    public ApiResponse<List<CreditLimitRequestResponse>> listCreditLimitRequests(
            @PathVariable Long teamId) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(adCreditLimitRequestService.findByScope(ScopeType.TEAM, teamId));
    }

    // ─────────────────────────────────────────────
    // 定期レポートスケジュール
    // ─────────────────────────────────────────────

    /** 定期レポートスケジュール一覧。 */
    @GetMapping("/report-schedules")
    public ApiResponse<List<ReportScheduleResponse>> listReportSchedules(
            @PathVariable Long teamId) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(adReportScheduleService.findByScope(ScopeType.TEAM, teamId));
    }

    // ─────────────────────────────────────────────
    // キャンペーンパフォーマンス
    // ─────────────────────────────────────────────

    /** キャンペーン別パフォーマンス（scope=TEAM）。 */
    @GetMapping("/campaigns/{campaignId}/performance")
    public ApiResponse<CampaignPerformanceResponse> getCampaignPerformance(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignPerformanceService.getPerformance(
                campaignId, ScopeType.TEAM, teamId, from, to));
    }
}
