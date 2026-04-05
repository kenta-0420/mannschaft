package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.BudgetReportStatus;
import com.mannschaft.app.budget.BudgetReportType;
import com.mannschaft.app.budget.dto.BudgetSummaryResponse;
import com.mannschaft.app.budget.dto.CreateReportRequest;
import com.mannschaft.app.budget.dto.DownloadUrlResponse;
import com.mannschaft.app.budget.dto.ReportResponse;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetReportEntity;
import com.mannschaft.app.budget.repository.BudgetReportRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 予算報告書サービス。非同期報告書生成・ダウンロードURL発行を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetReportService {

    private final BudgetReportRepository reportRepository;
    private final BudgetFiscalYearService fiscalYearService;
    private final BudgetSummaryService summaryService;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;
    private final StorageService storageService;

    private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(30);

    /**
     * 報告書生成をリクエストし、非同期で生成する。
     */
    @Transactional
    public ReportResponse generateReport(CreateReportRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        BudgetFiscalYearEntity fy = fiscalYearService.findById(request.fiscalYearId());
        accessControlService.checkAdminOrAbove(currentUserId, fy.getScopeId(), fy.getScopeType());

        BudgetReportEntity entity = BudgetReportEntity.builder()
                .fiscalYearId(request.fiscalYearId())
                .scopeType(fy.getScopeType())
                .scopeId(fy.getScopeId())
                .reportType(BudgetReportType.valueOf(request.reportType()))
                .periodStart(fy.getStartDate())
                .periodEnd(fy.getEndDate())
                .status(BudgetReportStatus.GENERATING)
                .generatedBy(currentUserId)
                .build();

        BudgetReportEntity saved = reportRepository.save(entity);
        log.info("報告書生成をリクエストしました: id={}", saved.getId());

        // 非同期で生成実行
        doGenerateReport(saved.getId());

        return budgetMapper.toReportResponse(saved);
    }

    /**
     * 報告書を非同期で生成する。
     */
    @Async("job-pool")
    @Transactional
    public void doGenerateReport(Long reportId) {
        BudgetReportEntity report = reportRepository.findById(reportId)
                .orElse(null);
        if (report == null) {
            log.warn("報告書が見つかりません: id={}", reportId);
            return;
        }

        try {
            // サマリデータを取得して報告書を生成
            BudgetSummaryResponse summary = summaryService.getFiscalYearSummary(report.getFiscalYearId());
            byte[] reportContent = buildReportContent(report, summary);

            // S3にアップロード
            String s3Key = "budget/reports/" + report.getFiscalYearId() + "/"
                    + report.getId() + "_" + System.currentTimeMillis() + ".csv";
            storageService.upload(s3Key, reportContent, "text/csv");

            report.markCompleted(s3Key, (long) reportContent.length);
            reportRepository.save(report);
            log.info("報告書生成が完了しました: id={}", reportId);
        } catch (Exception e) {
            log.error("報告書生成に失敗しました: id={}", reportId, e);
            report.markFailed();
            reportRepository.save(report);
        }
    }

    /**
     * 会計年度の報告書一覧を取得する。
     */
    public List<ReportResponse> listByFiscalYear(Long fiscalYearId) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        return reportRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .map(budgetMapper::toReportResponse)
                .toList();
    }

    /**
     * 報告書のダウンロードURL（S3 pre-signed）を取得する。
     */
    public DownloadUrlResponse getDownloadUrl(Long reportId) {
        BudgetReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_010));

        if (report.getStatus() != BudgetReportStatus.COMPLETED || report.getFileKey() == null) {
            throw new BusinessException(BudgetErrorCode.BUDGET_011);
        }

        String url = storageService.generateDownloadUrl(report.getFileKey(), DOWNLOAD_URL_TTL);
        return new DownloadUrlResponse(reportId, url, DOWNLOAD_URL_TTL.getSeconds());
    }

    // ========================================
    // ヘルパー
    // ========================================

    private byte[] buildReportContent(BudgetReportEntity report, BudgetSummaryResponse summary) {
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // BOM for Excel
        csv.append("カテゴリ,種別,予算額,実績額,残額,消化率(%),警告レベル\n");

        if (summary.categories() != null) {
            for (var cat : summary.categories()) {
                csv.append(escapeCsv(cat.categoryName())).append(',');
                csv.append(cat.categoryType()).append(',');
                csv.append(cat.budgetAmount()).append(',');
                csv.append(cat.actualAmount()).append(',');
                csv.append(cat.remainingAmount()).append(',');
                csv.append(cat.executionRate()).append(',');
                csv.append(cat.warningLevel()).append('\n');
            }
        }

        csv.append("\n合計,,");
        csv.append(summary.totalBudget()).append(',');
        csv.append(summary.totalExpense()).append(",,,\n");
        csv.append("収入合計,,,,").append(summary.totalIncome()).append(",,\n");
        csv.append("差引残高,,,,").append(summary.balance()).append(",,\n");

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
