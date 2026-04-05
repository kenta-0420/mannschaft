package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.CreateReportRequest;
import com.mannschaft.app.budget.dto.DownloadUrlResponse;
import com.mannschaft.app.budget.dto.ReportResponse;
import com.mannschaft.app.budget.service.BudgetReportService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 予算レポートコントローラー。
 * レポートの一覧取得・生成・ダウンロードURL取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget")
@RequiredArgsConstructor
public class BudgetReportController {

    private final BudgetReportService budgetReportService;

    /**
     * 会計年度に紐づくレポート一覧を取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @return レポート一覧
     */
    @GetMapping("/fiscal-years/{fiscalYearId}/reports")
    public ApiResponse<List<ReportResponse>> listByFiscalYear(@PathVariable Long fiscalYearId) {
        return ApiResponse.of(budgetReportService.listByFiscalYear(fiscalYearId));
    }

    /**
     * レポートを非同期で生成する。
     *
     * @param fiscalYearId 会計年度ID
     * @param request 生成リクエスト
     * @return 生成されたレポート情報
     */
    @PostMapping("/fiscal-years/{fiscalYearId}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReportResponse> generate(
            @PathVariable Long fiscalYearId,
            @Valid @RequestBody CreateReportRequest request) {
        return ApiResponse.of(budgetReportService.generateReport(request));
    }

    /**
     * レポートのダウンロードURLを取得する。
     *
     * @param reportId レポートID
     * @return ダウンロードURL
     */
    @GetMapping("/reports/{reportId}/download-url")
    public ApiResponse<DownloadUrlResponse> getDownloadUrl(@PathVariable Long reportId) {
        return ApiResponse.of(budgetReportService.getDownloadUrl(reportId));
    }
}
