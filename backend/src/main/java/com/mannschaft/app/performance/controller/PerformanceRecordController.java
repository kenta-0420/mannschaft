package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.BulkRecordRequest;
import com.mannschaft.app.performance.dto.BulkRecordResponse;
import com.mannschaft.app.performance.dto.CreateRecordRequest;
import com.mannschaft.app.performance.dto.ExportJobResponse;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.SelfRecordRequest;
import com.mannschaft.app.performance.dto.UpdateRecordRequest;
import com.mannschaft.app.performance.service.PerformanceExportService;
import com.mannschaft.app.performance.service.PerformanceRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.mannschaft.app.common.SecurityUtils;

/**
 * パフォーマンス記録コントローラー。記録のCRUD・一括入力・エクスポートAPIを提供する。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{teamId}/performance/records")
@Tag(name = "パフォーマンス記録", description = "F07.2 パフォーマンス記録CRUD・一括入力・エクスポート")
@RequiredArgsConstructor
public class PerformanceRecordController {

    private final PerformanceRecordService recordService;
    private final PerformanceExportService exportService;


    /**
     * パフォーマンス記録を入力する。
     */
    @PostMapping
    @Operation(summary = "パフォーマンス記録入力")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<RecordResponse>> createRecord(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRecordRequest request) {
        RecordResponse response = recordService.createRecord(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * パフォーマンス記録を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "パフォーマンス記録更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<RecordResponse>> updateRecord(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecordRequest request) {
        RecordResponse response = recordService.updateRecord(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * パフォーマンス記録を削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "パフォーマンス記録削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRecord(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        recordService.deleteRecord(teamId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 一括記録入力する。
     */
    @PostMapping("/bulk")
    @Operation(summary = "一括記録入力")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BulkRecordResponse>> createBulkRecords(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkRecordRequest request) {
        BulkRecordResponse response = recordService.createBulkRecords(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * MEMBER 自己記録入力する。
     */
    @PostMapping("/self")
    @Operation(summary = "MEMBER自己記録入力")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<RecordResponse>> createSelfRecord(
            @PathVariable Long teamId,
            @Valid @RequestBody SelfRecordRequest request) {
        RecordResponse response = recordService.createSelfRecord(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * パフォーマンス記録をCSVエクスポートする。
     */
    @GetMapping("/export")
    @Operation(summary = "パフォーマンス記録CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<?> exportRecords(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            HttpServletResponse response) throws Exception {

        long count = exportService.countExportRecords(teamId, metricId, userId, dateFrom, dateTo);

        if (count > 1000) {
            String jobId = "export-perf-" + System.currentTimeMillis();
            log.info("非同期パフォーマンスCSVエクスポート開始: teamId={}, jobId={}, count={}", teamId, jobId, count);
            ExportJobResponse jobResponse = new ExportJobResponse(jobId, "PROCESSING",
                    "エクスポートを開始しました。完了後に通知します。");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(jobResponse));
        }

        String filename = String.format("performance_records_%d_%s.csv",
                teamId, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        PrintWriter writer = response.getWriter();
        exportService.exportCsv(writer, teamId, metricId, userId, dateFrom, dateTo);
        writer.flush();

        return ResponseEntity.ok().build();
    }
}
