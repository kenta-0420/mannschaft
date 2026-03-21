package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityFieldStatsResponse;
import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;

/**
 * 活動記録統計・エクスポートコントローラー。
 */
@RestController
@RequestMapping("/api/v1/activities")
@Tag(name = "活動統計", description = "F06.4 活動統計・集計・エクスポート")
@RequiredArgsConstructor
public class ActivityStatsController {

    private final ActivityStatsService statsService;

    /**
     * 活動統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "活動統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityStatsResponse>> getStats(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "template_id", required = false) Long templateId,
            @RequestParam(value = "period", defaultValue = "MONTH") String period,
            @RequestParam(value = "date_from", required = false) LocalDate dateFrom,
            @RequestParam(value = "date_to", required = false) LocalDate dateTo) {
        ActivityStatsResponse response = statsService.getStats(
                ActivityScopeType.valueOf(scopeType), scopeId, templateId, period, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カスタムフィールドの集計データを取得する。
     */
    @GetMapping("/stats/fields")
    @Operation(summary = "フィールド集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityFieldStatsResponse>> getFieldStats(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam("template_id") Long templateId,
            @RequestParam("field_key") String fieldKey,
            @RequestParam(value = "period", defaultValue = "MONTH") String period) {
        ActivityFieldStatsResponse response = statsService.getFieldStats(
                ActivityScopeType.valueOf(scopeType), scopeId, templateId, fieldKey, period);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 活動記録をCSVエクスポートする。
     */
    @GetMapping("/export")
    @Operation(summary = "CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public void exportCsv(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "template_id", required = false) Long templateId,
            @RequestParam(value = "date_from", required = false) LocalDate dateFrom,
            @RequestParam(value = "date_to", required = false) LocalDate dateTo,
            HttpServletResponse response) {
        statsService.exportCsv(
                ActivityScopeType.valueOf(scopeType), scopeId, templateId, dateFrom, dateTo, response);
    }
}
