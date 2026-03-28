package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.Granularity;
import com.mannschaft.app.analytics.dto.*;
import com.mannschaft.app.analytics.service.AnalyticsAggregationService;
import com.mannschaft.app.analytics.service.AnalyticsCsvExportService;
import com.mannschaft.app.analytics.service.AnalyticsForecastService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 経営分析ダッシュボード: 収益・予測・エクスポート。
 */
@RestController
@RequestMapping("/api/v1/system-admin/analytics")
@RequiredArgsConstructor
public class AnalyticsRevenueController {

    private final AnalyticsAggregationService aggregationService;
    private final AnalyticsCsvExportService csvExportService;
    private final AnalyticsForecastService forecastService;

    /** 収益サマリ（MRR/ARR/ARPU/LTV/NRR/Quick Ratio） */
    @GetMapping("/revenue/summary")
    public ApiResponse<RevenueSummaryResponse> getRevenueSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.of(aggregationService.getRevenueSummary(date));
    }

    /** 収益推移（日次/週次/月次） */
    @GetMapping("/revenue/trend")
    public ApiResponse<RevenueTrendResponse> getRevenueTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false, defaultValue = "DAILY") Granularity granularity) {
        return ApiResponse.of(aggregationService.getRevenueTrend(from, to, preset, granularity));
    }

    /** 収益源別内訳 */
    @GetMapping("/revenue/by-source")
    public ApiResponse<RevenueBySourceResponse> getRevenueBySource(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset) {
        return ApiResponse.of(aggregationService.getRevenueBySource(from, to, preset));
    }

    /** 収益予測（3/6/12ヶ月） */
    @GetMapping("/forecast")
    public ApiResponse<ForecastResponse> getForecast(
            @RequestParam(required = false, defaultValue = "6") int months) {
        return ApiResponse.of(forecastService.forecast(months));
    }

    /** 分析データCSVエクスポート */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset) {
        String csv = csvExportService.exportCsv(type, from, to, preset);
        String filename = "analytics_" + type.toLowerCase() + "_" +
            (from != null ? from : "preset") + "_" + (to != null ? to : "preset") + ".csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
