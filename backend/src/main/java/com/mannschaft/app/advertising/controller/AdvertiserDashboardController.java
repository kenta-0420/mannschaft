package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.PricingModel;
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
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.dto.RateSimulatorResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.dto.ReportScheduleResponse;
import com.mannschaft.app.advertising.dto.UpdateAdvertiserAccountRequest;
import com.mannschaft.app.advertising.service.AdCreditLimitRequestService;
import com.mannschaft.app.advertising.service.AdInvoiceService;
import com.mannschaft.app.advertising.service.AdRateCardService;
import com.mannschaft.app.advertising.service.AdReportScheduleService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.advertising.service.CampaignPerformanceService;
import com.mannschaft.app.advertising.service.CsvExportService;
import com.mannschaft.app.advertising.service.InvoicePdfService;
import com.mannschaft.app.advertising.service.RateSimulatorService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 広告主ダッシュボードコントローラー。
 * <p>
 * 広告主向けのアカウント管理・料金シミュレーション・概要表示を提供する。
 */
@RestController
@RequestMapping("/api/v1/advertiser")
@RequiredArgsConstructor
public class AdvertiserDashboardController {

    private final AdvertiserAccountService advertiserAccountService;
    private final AdRateCardService adRateCardService;
    private final RateSimulatorService rateSimulatorService;
    private final AdInvoiceService adInvoiceService;
    private final AdReportScheduleService adReportScheduleService;
    private final AdCreditLimitRequestService adCreditLimitRequestService;
    private final CampaignPerformanceService campaignPerformanceService;
    private final InvoicePdfService invoicePdfService;
    private final CsvExportService csvExportService;

    /**
     * 広告主アカウントを新規登録する。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdvertiserAccountResponse> register(
            @RequestParam Long organizationId,
            @Valid @RequestBody RegisterAdvertiserRequest request) {
        return ApiResponse.of(advertiserAccountService.register(organizationId, request));
    }

    /**
     * 自組織の広告主アカウント情報を取得する。
     */
    @GetMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> getAccount(@RequestParam Long organizationId) {
        return ApiResponse.of(advertiserAccountService.getByOrganizationId(organizationId));
    }

    /**
     * 広告主アカウントのプロフィールを更新する。
     */
    @PatchMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> updateAccount(
            @RequestParam Long organizationId,
            @Valid @RequestBody UpdateAdvertiserAccountRequest request) {
        return ApiResponse.of(advertiserAccountService.updateProfile(organizationId, request));
    }

    /**
     * 料金シミュレーションを実行する。
     * <p>
     * 認証必須だが広告主登録は不要。
     */
    @GetMapping("/rate-simulator")
    public ApiResponse<RateSimulatorResponse> rateSimulator(
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String template,
            @RequestParam PricingModel pricingModel,
            @RequestParam(required = false) Integer impressions,
            @RequestParam(required = false) Integer clicks,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.of(rateSimulatorService.simulate(
                prefecture, template, pricingModel, impressions, clicks, days));
    }

    /**
     * 公開料金カード一覧を取得する。
     * <p>
     * 認証必須だが広告主登録は不要。
     */
    @GetMapping("/rate-cards")
    public ApiResponse<List<PublicRateCardResponse>> rateCards(
            @RequestParam(required = false) PricingModel pricingModel,
            @RequestParam(required = false) String prefecture) {
        return ApiResponse.of(adRateCardService.findCurrentRateCards(pricingModel, prefecture));
    }

    /**
     * 広告主ダッシュボード概要を取得する。
     */
    @GetMapping("/overview")
    public ApiResponse<AdvertiserOverviewResponse> overview(@RequestParam Long organizationId) {
        // TODO: Phase 2 で ad_daily_stats テーブルと連携し、実際の統計データを返す
        advertiserAccountService.getByOrganizationId(organizationId); // 存在チェック
        LocalDate now = LocalDate.now();
        return ApiResponse.of(new AdvertiserOverviewResponse(
                new AdvertiserOverviewResponse.Period(now.withDayOfMonth(1), now),
                0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of()
        ));
    }

    // ─────────────────────────────────────────────
    // 請求書
    // ─────────────────────────────────────────────

    /**
     * 請求書一覧を取得する。
     */
    @GetMapping("/invoices")
    public PagedResponse<InvoiceSummaryResponse> listInvoices(
            @RequestParam Long organizationId,
            @RequestParam(required = false) InvoiceStatus status,
            Pageable pageable) {
        AdvertiserAccountResponse account = advertiserAccountService.getByOrganizationId(organizationId);
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

    /**
     * 請求書詳細を取得する（明細付き）。
     */
    @GetMapping("/invoices/{invoiceId}")
    public ApiResponse<InvoiceDetailResponse> getInvoice(
            @PathVariable Long invoiceId,
            @RequestParam Long organizationId) {
        AdvertiserAccountResponse account = advertiserAccountService.getByOrganizationId(organizationId);
        return ApiResponse.of(adInvoiceService.getDetail(invoiceId, account.id()));
    }

    // ─────────────────────────────────────────────
    // 定期レポートスケジュール
    // ─────────────────────────────────────────────

    /**
     * 定期レポートスケジュール一覧を取得する。
     */
    @GetMapping("/report-schedules")
    public ApiResponse<List<ReportScheduleResponse>> listReportSchedules(
            @RequestParam Long organizationId) {
        return ApiResponse.of(adReportScheduleService.findByOrganizationId(organizationId));
    }

    /**
     * 定期レポートスケジュールを作成する。
     */
    @PostMapping("/report-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportScheduleResponse> createReportSchedule(
            @RequestParam Long organizationId,
            @Valid @RequestBody CreateReportScheduleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(adReportScheduleService.create(organizationId, userId, request));
    }

    /**
     * 定期レポートスケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/report-schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReportSchedule(
            @PathVariable Long id,
            @RequestParam Long organizationId) {
        adReportScheduleService.delete(id, organizationId);
    }

    // ─────────────────────────────────────────────
    // credit_limit 増額申請
    // ─────────────────────────────────────────────

    /**
     * credit_limit 増額申請を作成する。
     */
    @PostMapping("/credit-limit-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreditLimitRequestResponse> createCreditLimitRequest(
            @RequestParam Long organizationId,
            @Valid @RequestBody CreateCreditLimitRequest request) {
        return ApiResponse.of(adCreditLimitRequestService.create(organizationId, request));
    }

    /**
     * 自組織の増額申請履歴を取得する。
     */
    @GetMapping("/credit-limit-requests")
    public ApiResponse<List<CreditLimitRequestResponse>> listCreditLimitRequests(
            @RequestParam Long organizationId) {
        return ApiResponse.of(adCreditLimitRequestService.findByOrganizationId(organizationId));
    }

    // ─────────────────────────────────────────────
    // キャンペーンパフォーマンス
    // ─────────────────────────────────────────────

    /**
     * キャンペーン別パフォーマンスを取得する。
     */
    @GetMapping("/campaigns/{campaignId}/performance")
    public ApiResponse<CampaignPerformanceResponse> getCampaignPerformance(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ApiResponse.of(campaignPerformanceService.getPerformance(campaignId, organizationId, from, to));
    }

    /**
     * クリエイティブ別比較を取得する（A/B テスト支援）。
     */
    @GetMapping("/campaigns/{campaignId}/creatives")
    public ApiResponse<CreativeComparisonResponse> getCreativeComparison(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ApiResponse.of(campaignPerformanceService.getCreativeComparison(campaignId, organizationId, from, to));
    }

    /**
     * 地域×テンプレート別内訳を取得する。
     */
    @GetMapping("/campaigns/{campaignId}/breakdown")
    public ApiResponse<BreakdownResponse> getBreakdown(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) String breakdownBy) {
        return ApiResponse.of(campaignPerformanceService.getBreakdown(campaignId, organizationId, from, to, breakdownBy));
    }

    /**
     * パフォーマンスデータを CSV エクスポートする。
     */
    @GetMapping("/campaigns/{campaignId}/export")
    public ResponseEntity<byte[]> exportCampaignPerformance(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        byte[] csv = csvExportService.exportCampaignPerformance(campaignId, organizationId, from, to);
        String filename = csvExportService.getCsvFilename(campaignId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    // ─────────────────────────────────────────────
    // 請求書 PDF
    // ─────────────────────────────────────────────

    /**
     * 請求書 PDF をダウンロードする。
     */
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @PathVariable Long invoiceId,
            @RequestParam Long organizationId) {
        AdvertiserAccountResponse account = advertiserAccountService.getByOrganizationId(organizationId);
        byte[] pdf = invoicePdfService.generateInvoicePdf(invoiceId, account.id());
        String filename = invoicePdfService.getFilename(invoiceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
