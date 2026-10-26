package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.AdvertiserOverviewResponse;
import com.mannschaft.app.advertising.dto.BreakdownResponse;
import com.mannschaft.app.advertising.dto.CampaignPerformanceResponse;
import com.mannschaft.app.advertising.dto.CreateCreditLimitRequest;
import com.mannschaft.app.advertising.dto.CreateReportScheduleRequest;
import com.mannschaft.app.advertising.dto.CreativeComparisonResponse;
import com.mannschaft.app.advertising.dto.CreditLimitRequestResponse;
import com.mannschaft.app.advertising.dto.InvoiceDetailResponse;
import com.mannschaft.app.advertising.dto.InvoiceSummaryResponse;
import com.mannschaft.app.advertising.dto.ReportScheduleResponse;
import com.mannschaft.app.advertising.dto.UpdateAdvertiserAccountRequest;
import com.mannschaft.app.advertising.service.AdCreditLimitRequestService;
import com.mannschaft.app.advertising.service.AdInvoiceService;
import com.mannschaft.app.advertising.service.AdReportScheduleService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.advertising.service.CampaignPerformanceService;
import com.mannschaft.app.advertising.service.CsvExportService;
import com.mannschaft.app.advertising.service.InvoicePdfService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * チームスコープ 広告主ダッシュボードコントローラー（F09.19 §9 / §16 F09.19.5・5b）。
 *
 * <p>組織版 {@link AdvertiserDashboardController} の account / overview / invoices /
 * credit-limit-requests / report-schedules / performance / creatives / breakdown / export を
 * scope=TEAM で対称提供する。認可は当該チーム ADMIN 以上
 * （{@code AccessControlService.checkAdminOrAbove}）。他チームの ADMIN は越境として 403（COMMON_002）。
 * 応答形式は org 版と同一（invoices は {@link PagedResponse} = data + meta、他は {@link ApiResponse} = data）。</p>
 *
 * <p><b>IDOR 対策（§11）</b>: 請求書詳細 / PDF は scope→{@code advertiser_account.id} を解決して
 * accountId ベースの Service に委譲し、campaign 系（performance / creatives / breakdown / export）は
 * Service 内部の {@code findCampaignWithAuth(campaignId, TEAM, teamId)} で campaign→account→scope の
 * 帰属を検証する。他チームのリソースは存在有無を問わず 403。</p>
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
    private final InvoicePdfService invoicePdfService;
    private final CsvExportService csvExportService;
    private final AccessControlService accessControlService;

    /** チームスコープの権限検証。指定チームの ADMIN 以上であることを確認する（越境は 403）。 */
    private void verifyTeamAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
    }

    // ─────────────────────────────────────────────
    // 広告主アカウント
    // ─────────────────────────────────────────────

    /** 自チームの広告主アカウント情報を取得する。 */
    @GetMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> getAccount(@PathVariable Long teamId) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(advertiserAccountService.getByScope(ScopeType.TEAM, teamId));
    }

    /** 広告主アカウントのプロフィールを更新する。 */
    @PatchMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> updateAccount(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateAdvertiserAccountRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(advertiserAccountService.updateProfile(ScopeType.TEAM, teamId, request));
    }

    /** 広告主ダッシュボード概要（scope=TEAM）。 */
    @GetMapping("/overview")
    public ApiResponse<AdvertiserOverviewResponse> overview(@PathVariable Long teamId) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignPerformanceService.getOverview(ScopeType.TEAM, teamId));
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

    /** 請求書詳細（明細付き）。scope→account.id を解決し帰属検証済みの詳細を返す。 */
    @GetMapping("/invoices/{invoiceId}")
    public ApiResponse<InvoiceDetailResponse> getInvoice(
            @PathVariable Long teamId,
            @PathVariable Long invoiceId) {
        verifyTeamAccess(teamId);
        AdvertiserAccountResponse account = advertiserAccountService.getByScope(ScopeType.TEAM, teamId);
        return ApiResponse.of(adInvoiceService.getDetail(invoiceId, account.id()));
    }

    /** 請求書 PDF をダウンロードする。scope→account.id を解決し帰属検証済みで生成する。 */
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @PathVariable Long teamId,
            @PathVariable Long invoiceId) {
        verifyTeamAccess(teamId);
        AdvertiserAccountResponse account = advertiserAccountService.getByScope(ScopeType.TEAM, teamId);
        byte[] pdf = invoicePdfService.generateInvoicePdf(invoiceId, account.id());
        String filename = invoicePdfService.getFilename(invoiceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
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

    /** 増額申請を作成する（scope=TEAM）。 */
    @PostMapping("/credit-limit-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreditLimitRequestResponse> createCreditLimitRequest(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCreditLimitRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(adCreditLimitRequestService.create(ScopeType.TEAM, teamId, request));
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

    /** 定期レポートスケジュールを作成する（scope=TEAM）。 */
    @PostMapping("/report-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportScheduleResponse> createReportSchedule(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateReportScheduleRequest request) {
        verifyTeamAccess(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(adReportScheduleService.create(ScopeType.TEAM, teamId, userId, request));
    }

    /** 定期レポートスケジュールを削除する（論理削除・scope=TEAM）。 */
    @DeleteMapping("/report-schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReportSchedule(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        verifyTeamAccess(teamId);
        adReportScheduleService.delete(id, ScopeType.TEAM, teamId);
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

    /** クリエイティブ別比較（A/B テスト支援・scope=TEAM）。 */
    @GetMapping("/campaigns/{campaignId}/creatives")
    public ApiResponse<CreativeComparisonResponse> getCreativeComparison(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignPerformanceService.getCreativeComparison(
                campaignId, ScopeType.TEAM, teamId, from, to));
    }

    /** 地域×テンプレート別内訳（scope=TEAM）。 */
    @GetMapping("/campaigns/{campaignId}/breakdown")
    public ApiResponse<BreakdownResponse> getBreakdown(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) String breakdownBy) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(campaignPerformanceService.getBreakdown(
                campaignId, ScopeType.TEAM, teamId, from, to, breakdownBy));
    }

    /** パフォーマンスデータを CSV エクスポートする（scope=TEAM）。 */
    @GetMapping("/campaigns/{campaignId}/export")
    public ResponseEntity<byte[]> exportCampaignPerformance(
            @PathVariable Long teamId,
            @PathVariable Long campaignId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        verifyTeamAccess(teamId);
        byte[] csv = csvExportService.exportCampaignPerformance(campaignId, ScopeType.TEAM, teamId, from, to);
        String filename = csvExportService.getCsvFilename(campaignId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }
}
