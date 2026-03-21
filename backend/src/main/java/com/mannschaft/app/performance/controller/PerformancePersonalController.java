package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.MyPerformanceResponse;
import com.mannschaft.app.performance.dto.TemplateListResponse;
import com.mannschaft.app.performance.service.PerformanceMetricService;
import com.mannschaft.app.performance.service.PerformanceStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * パフォーマンス個人・テンプレートコントローラー。
 * チーム横断の個人パフォーマンス・指標テンプレート一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/performance")
@Tag(name = "パフォーマンス個人", description = "F07.2 個人パフォーマンス・指標テンプレート")
@RequiredArgsConstructor
public class PerformancePersonalController {

    private final PerformanceStatsService statsService;
    private final PerformanceMetricService metricService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分のパフォーマンスを全チーム横断で取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "自分のパフォーマンス（全チーム横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MyPerformanceResponse>>> getMyPerformance(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        List<MyPerformanceResponse> response = statsService.getMyPerformance(
                getCurrentUserId(), teamId, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 指標テンプレート一覧を取得する。
     */
    @GetMapping("/metric-templates")
    @Operation(summary = "指標テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TemplateListResponse>> listTemplates(
            @RequestParam(required = false) String sportCategory) {
        TemplateListResponse response = metricService.listTemplates(sportCategory);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
