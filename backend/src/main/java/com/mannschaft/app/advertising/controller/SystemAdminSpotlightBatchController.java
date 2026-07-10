package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.service.AdDailyStatsAggregationBatchService;
import com.mannschaft.app.advertising.service.MonthlyInvoiceBatchService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

/**
 * F09.19.3 spotlight バッチ手動トリガー（SYSTEM_ADMIN 専用。正本 §6.1・§16 AC-3.3/3.7）。
 *
 * <p>{@code /api/v1/system-admin/**} 配下は {@code SecurityConfig} で SYSTEM_ADMIN ロールに一括制限
 * されているため URL prefix だけで非 SYSTEM_ADMIN は 403 になる。多層防御として
 * クラスに {@code @PreAuthorize} も併記する（.5 {@code AdvertiserAdminController} の前例に整合）。</p>
 *
 * <ul>
 *   <li>{@code POST /daily-stats/run?targetDate=} — 日次集計の手動実行（未指定なら前日）。冪等</li>
 *   <li>{@code POST /invoices/run?month=} — 月次請求の対象月指定・手動実行（当日 E2E クローズ用）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system-admin/spotlight")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "システム管理 - spotlight バッチ", description = "F09.19.3 日次集計・月次請求の手動トリガー")
public class SystemAdminSpotlightBatchController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final AdDailyStatsAggregationBatchService dailyStatsAggregationBatchService;
    private final MonthlyInvoiceBatchService monthlyInvoiceBatchService;

    /**
     * 日次集計を手動実行する（F09.19.3 §16 AC-3.3）。冪等。
     *
     * @param targetDate 集計対象日（未指定なら前日）
     */
    @PostMapping("/daily-stats/run")
    @Operation(summary = "日次集計の手動実行", description = "指定日（未指定なら前日）の運用型 imp/click を集計して ad_daily_stats へ UPSERT する（冪等）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runDailyStats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        LocalDate resolved = (targetDate != null) ? targetDate : LocalDate.now(ZONE).minusDays(1);
        log.info("F09.19.3 日次集計 手動実行: targetDate={}", resolved);
        dailyStatsAggregationBatchService.aggregate(resolved);
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "targetDate", resolved.toString(),
                "status", "COMPLETED")));
    }

    /**
     * 月次請求を対象月指定で手動実行する（F09.19.3 §16 AC-3.7）。
     *
     * @param month 対象月（{@code yyyy-MM}）
     */
    @PostMapping("/invoices/run")
    @Operation(summary = "月次請求の手動実行", description = "指定月の広告主請求書を生成する（DRAFT のみ再生成・冪等）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runInvoices(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        log.info("F09.19.3 月次請求 手動実行: month={}", month);
        monthlyInvoiceBatchService.generateMonthlyInvoices(month);
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "month", month.toString(),
                "status", "COMPLETED")));
    }
}
