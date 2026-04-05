package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.Granularity;
import com.mannschaft.app.analytics.SegmentType;
import com.mannschaft.app.analytics.dto.*;
import com.mannschaft.app.analytics.service.AnalyticsAggregationService;
import com.mannschaft.app.analytics.service.SegmentCalculationService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 経営分析ダッシュボード: ユーザー・コホート・ファネル・モジュール・セグメント・広告。
 */
@RestController
@RequestMapping("/api/v1/system-admin/analytics")
@RequiredArgsConstructor
public class AnalyticsUserController {

    private final AnalyticsAggregationService aggregationService;
    private final SegmentCalculationService segmentService;

    /** ユーザー動態推移 */
    @GetMapping("/users/trend")
    public ApiResponse<UserTrendResponse> getUserTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false, defaultValue = "DAILY") Granularity granularity) {
        return ApiResponse.of(aggregationService.getUserTrend(from, to, preset, granularity));
    }

    /** 解約分析 */
    @GetMapping("/churn")
    public ApiResponse<ChurnAnalysisResponse> getChurnAnalysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset) {
        return ApiResponse.of(aggregationService.getChurnAnalysis(from, to, preset));
    }

    /** コホート分析 */
    @GetMapping("/cohorts")
    public ApiResponse<CohortAnalysisResponse> getCohortAnalysis(
            @RequestParam(required = false) String from_cohort,
            @RequestParam(required = false) String to_cohort,
            @RequestParam(required = false, defaultValue = "RETENTION") String metric) {
        return ApiResponse.of(aggregationService.getCohortAnalysis(from_cohort, to_cohort, metric));
    }

    /** 課金ファネル */
    @GetMapping("/funnel")
    public ApiResponse<FunnelResponse> getFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.of(aggregationService.getFunnelAnalysis(date));
    }

    /** モジュールランキング */
    @GetMapping("/modules/ranking")
    public ApiResponse<ModuleRankingResponse> getModuleRanking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false, defaultValue = "REVENUE") String sort_by) {
        return ApiResponse.of(aggregationService.getModuleRanking(from, to, preset, sort_by));
    }

    /** セグメント別分析 */
    @GetMapping("/segments")
    public ApiResponse<SegmentAnalysisResponse> getSegments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam SegmentType segment_by) {
        return ApiResponse.of(segmentService.analyze(from, to, preset, segment_by));
    }

    /** 広告収益分析 */
    @GetMapping("/ads")
    public ApiResponse<AdAnalyticsResponse> getAdAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false, defaultValue = "DAILY") Granularity granularity) {
        return ApiResponse.of(aggregationService.getAdAnalytics(from, to, preset, granularity));
    }
}
