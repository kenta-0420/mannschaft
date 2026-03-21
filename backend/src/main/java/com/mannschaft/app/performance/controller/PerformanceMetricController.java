package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.CreateMetricRequest;
import com.mannschaft.app.performance.dto.FromTemplateRequest;
import com.mannschaft.app.performance.dto.FromTemplateResponse;
import com.mannschaft.app.performance.dto.LinkableFieldResponse;
import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.dto.SortOrderRequest;
import com.mannschaft.app.performance.dto.SortOrderResponse;
import com.mannschaft.app.performance.dto.UpdateMetricRequest;
import com.mannschaft.app.performance.service.PerformanceMetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * パフォーマンス指標コントローラー。指標定義のCRUD・テンプレート・並び順管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/performance/metrics")
@Tag(name = "パフォーマンス指標", description = "F07.2 パフォーマンス指標定義CRUD・テンプレート・並び順管理")
@RequiredArgsConstructor
public class PerformanceMetricController {

    private final PerformanceMetricService metricService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 指標定義一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "指標定義一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MetricResponse>>> listMetrics(
            @PathVariable Long teamId) {
        List<MetricResponse> metrics = metricService.listMetrics(teamId);
        return ResponseEntity.ok(ApiResponse.of(metrics));
    }

    /**
     * 指標定義を作成する。
     */
    @PostMapping
    @Operation(summary = "指標定義作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MetricResponse>> createMetric(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateMetricRequest request) {
        MetricResponse response = metricService.createMetric(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 指標定義を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "指標定義更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MetricResponse>> updateMetric(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateMetricRequest request) {
        MetricResponse response = metricService.updateMetric(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 指標定義を無効化する（is_active = false）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "指標定義無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateMetric(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        metricService.deactivateMetric(teamId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * テンプレートから指標を一括作成する。
     */
    @PostMapping("/from-template")
    @Operation(summary = "テンプレートから指標一括作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FromTemplateResponse>> createFromTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody FromTemplateRequest request) {
        FromTemplateResponse response = metricService.createFromTemplate(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 指標の並び順を一括更新する。
     */
    @PatchMapping("/sort-order")
    @Operation(summary = "指標並び順一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SortOrderResponse>> updateSortOrder(
            @PathVariable Long teamId,
            @Valid @RequestBody SortOrderRequest request) {
        SortOrderResponse response = metricService.updateSortOrder(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 活動記録連携可能なカスタムフィールド一覧を取得する。
     */
    @GetMapping("/linkable-fields")
    @Operation(summary = "活動記録連携可能フィールド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<LinkableFieldResponse>>> listLinkableFields(
            @PathVariable Long teamId) {
        List<LinkableFieldResponse> fields = metricService.listLinkableFields(teamId);
        return ResponseEntity.ok(ApiResponse.of(fields));
    }
}
