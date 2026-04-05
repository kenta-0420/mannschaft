package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.ChartRecordResponse;
import com.mannschaft.app.chart.dto.ChartRecordSummaryResponse;
import com.mannschaft.app.chart.dto.CopyChartRequest;
import com.mannschaft.app.chart.dto.CreateChartRecordRequest;
import com.mannschaft.app.chart.dto.PinChartRequest;
import com.mannschaft.app.chart.dto.PinResponse;
import com.mannschaft.app.chart.dto.ShareChartRequest;
import com.mannschaft.app.chart.dto.ShareResponse;
import com.mannschaft.app.chart.dto.UpdateChartRecordRequest;
import com.mannschaft.app.chart.service.ChartRecordService;
import com.mannschaft.app.chart.service.ChartSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * カルテレコードコントローラー。カルテのCRUD・コピー・共有・ピン留め・PDF・顧客別一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts")
@Tag(name = "カルテ", description = "F07.4 カルテCRUD・コピー・共有・ピン留め")
@RequiredArgsConstructor
public class ChartRecordController {

    private final ChartRecordService chartRecordService;
    private final ChartSettingsService chartSettingsService;
    private final PdfGeneratorService pdfGeneratorService;

    /**
     * 1. カルテ一覧取得
     * GET /api/v1/teams/{teamId}/charts
     */
    @GetMapping
    @Operation(summary = "カルテ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ChartRecordSummaryResponse>> listCharts(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long customerUserId,
            @RequestParam(required = false) Long staffUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDateTo,
            @RequestParam(required = false) Boolean isSharedToCustomer,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ChartRecordSummaryResponse> result = chartRecordService.listCharts(
                teamId, customerUserId, staffUserId, visitDateFrom, visitDateTo,
                isSharedToCustomer, keyword, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 2. カルテ作成
     * POST /api/v1/teams/{teamId}/charts
     */
    @PostMapping
    @Operation(summary = "カルテ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ChartRecordResponse>> createChart(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateChartRecordRequest request) {
        ChartRecordResponse response = chartRecordService.createChart(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 3. カルテ詳細取得
     * GET /api/v1/teams/{teamId}/charts/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "カルテ詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ChartRecordResponse>> getChart(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ChartRecordResponse response = chartRecordService.getChart(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 4. カルテ更新
     * PUT /api/v1/teams/{teamId}/charts/{id}
     */
    @PutMapping("/{id}")
    @Operation(summary = "カルテ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ChartRecordResponse>> updateChart(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateChartRecordRequest request) {
        ChartRecordResponse response = chartRecordService.updateChart(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 5. カルテ削除（論理削除）
     * DELETE /api/v1/teams/{teamId}/charts/{id}
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "カルテ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteChart(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        chartRecordService.deleteChart(teamId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 15. カルテコピー
     * POST /api/v1/teams/{teamId}/charts/{id}/copy
     */
    @PostMapping("/{id}/copy")
    @Operation(summary = "カルテコピー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "コピー成功")
    public ResponseEntity<ApiResponse<ChartRecordResponse>> copyChart(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody CopyChartRequest request) {
        ChartRecordResponse response = chartRecordService.copyChart(teamId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 16. カルテPDFエクスポート
     * GET /api/v1/teams/{teamId}/charts/{id}/pdf
     */
    @GetMapping("/{id}/pdf")
    @Operation(summary = "カルテPDFエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF生成成功")
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ChartRecordResponse chart = chartRecordService.getChartForPdf(teamId, id);
        List<Map<String, String>> photoBase64List = chartRecordService.getPhotoBase64List(id);

        Map<String, Object> variables = new HashMap<>();
        variables.put("chart", chart);
        variables.put("title", "カルテ");
        variables.put("photos", photoBase64List);

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/chart-record", variables);

        String fileName = PdfFileNameBuilder.of("カルテ")
                .date(chart.getVisitDate())
                .identifier(chart.getCustomerDisplayName() + "様")
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }

    /**
     * 17. 顧客共有設定の変更
     * PATCH /api/v1/teams/{teamId}/charts/{id}/share
     */
    @PatchMapping("/{id}/share")
    @Operation(summary = "顧客共有設定変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShareResponse>> updateShareStatus(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ShareChartRequest request) {
        ShareResponse response = chartRecordService.updateShareStatus(teamId, id, request.getIsSharedToCustomer());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 18. 特定顧客の全カルテ一覧
     * GET /api/v1/teams/{teamId}/charts/customer/{userId}
     */
    @GetMapping("/customer/{userId}")
    @Operation(summary = "顧客別カルテ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ChartRecordSummaryResponse>> listCustomerCharts(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ChartRecordSummaryResponse> result = chartRecordService.listCustomerCharts(
                teamId, userId, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 26. カルテのピン留め切替
     * PATCH /api/v1/teams/{teamId}/charts/{id}/pin
     */
    @PatchMapping("/{id}/pin")
    @Operation(summary = "ピン留め切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<PinResponse>> updatePinStatus(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody PinChartRequest request) {
        PinResponse response = chartRecordService.updatePinStatus(teamId, id, request.getIsPinned());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 27. 経過グラフ用データ取得
     * GET /api/v1/teams/{teamId}/charts/customer/{userId}/progress
     */
    @GetMapping("/customer/{userId}/progress")
    @Operation(summary = "経過グラフ用データ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<com.mannschaft.app.chart.dto.ProgressResponse>> getProgress(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestParam(required = false) String fieldIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDateTo) {
        com.mannschaft.app.chart.dto.ProgressResponse response = chartSettingsService.getProgressData(
                teamId, userId, fieldIds, visitDateFrom, visitDateTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
