package com.mannschaft.app.errorreport.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.errorreport.ErrorReportMapper;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * システム管理者向けエラーレポート管理コントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/error-reports")
@Tag(name = "システム管理 - エラーレポート", description = "F12.5 エラーレポート管理API（システム管理者向け）")
@RequiredArgsConstructor
public class SystemAdminErrorReportController {

    private final ErrorReportService errorReportService;
    private final ErrorReportMapper errorReportMapper;

    /**
     * エラーレポート一覧を取得する（ページネーション・フィルタ付き）。
     */
    @GetMapping
    @Operation(summary = "エラーレポート一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ErrorReportResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "lastOccurredAt,desc") String sort) {
        int cappedSize = Math.min(size, 100);
        Pageable pageable = buildPageable(page, cappedSize, sort);
        Page<ErrorReportEntity> result = errorReportService.search(status, severity, from, to, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(
                errorReportMapper.toResponseList(result.getContent()), meta));
    }

    /**
     * エラーレポート詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "エラーレポート詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> get(@PathVariable Long id) {
        ErrorReportEntity entity = errorReportService.findById(id);
        return ResponseEntity.ok(ApiResponse.of(errorReportMapper.toResponse(entity)));
    }

    /**
     * エラーレポートのステータス・重要度・管理者メモを更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "エラーレポート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ErrorReportUpdateRequest request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        ErrorReportEntity entity = errorReportService.updateStatus(id, request, adminId);
        return ResponseEntity.ok(ApiResponse.of(errorReportMapper.toResponse(entity)));
    }

    /**
     * エラーレポートを一括でステータス更新する。
     */
    @PatchMapping("/bulk")
    @Operation(summary = "エラーレポート一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Map<String, Integer>> bulkUpdate(
            @Valid @RequestBody ErrorReportBulkUpdateRequest request) {
        int count = errorReportService.bulkUpdate(request);
        return ResponseEntity.ok(Map.of("updated_count", count));
    }

    /**
     * エラーレポート統計情報を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "エラーレポート統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ErrorReportStatsResponse> stats() {
        return ResponseEntity.ok(errorReportService.getStats());
    }

    /**
     * sort パラメータ文字列から Pageable を構築する。
     * 形式: "field,direction" (例: "lastOccurredAt,desc")
     */
    private Pageable buildPageable(int page, int size, String sort) {
        String[] parts = sort.split(",");
        if (parts.length == 2) {
            Sort.Direction direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElse(Sort.Direction.DESC);
            return PageRequest.of(page, size, Sort.by(direction, parts[0].trim()));
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastOccurredAt"));
    }
}
