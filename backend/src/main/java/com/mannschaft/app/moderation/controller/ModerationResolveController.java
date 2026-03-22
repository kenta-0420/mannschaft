package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.BulkResolveRequest;
import com.mannschaft.app.moderation.dto.EscalateRequest;
import com.mannschaft.app.moderation.dto.ReportActionResponse;
import com.mannschaft.app.moderation.dto.ReportStatsResponse;
import com.mannschaft.app.moderation.dto.ResolveReportRequest;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.service.ReportActionService;
import com.mannschaft.app.moderation.service.UserViolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 通報対応コントローラー。レビュー・対応・却下・差し戻し・エスカレーション等のAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
@Tag(name = "通報対応管理", description = "F10.1 通報対応・レビュー管理API")
@RequiredArgsConstructor
public class ModerationResolveController {

    private final ReportActionService reportActionService;
    private final UserViolationService userViolationService;


    /**
     * 通報のレビューを開始する。
     */
    @PatchMapping("/{id}/review")
    @Operation(summary = "レビュー開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "レビュー開始成功")
    public ResponseEntity<ApiResponse<Void>> startReview(@PathVariable Long id) {
        reportActionService.startReview(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * 通報に対して対応アクションを実行する。
     */
    @PatchMapping("/{id}/resolve")
    @Operation(summary = "通報対応実施")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "対応成功")
    public ResponseEntity<ApiResponse<ReportActionResponse>> resolveReport(
            @PathVariable Long id,
            @Valid @RequestBody ResolveReportRequest request) {
        ReportActionResponse response = reportActionService.resolveReport(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通報を却下する。
     */
    @PatchMapping("/{id}/dismiss")
    @Operation(summary = "通報却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "却下成功")
    public ResponseEntity<ApiResponse<ReportActionResponse>> dismissReport(
            @PathVariable Long id,
            @RequestParam(required = false) @Size(max = 2000) String note) {
        ReportActionResponse response = reportActionService.dismissReport(id, note, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通報を一括対応する。
     */
    @PostMapping("/bulk-resolve")
    @Operation(summary = "通報一括対応")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括対応成功")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> bulkResolve(
            @Valid @RequestBody BulkResolveRequest request) {
        int count = reportActionService.bulkResolve(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(Map.of("resolvedCount", count)));
    }

    /**
     * 通報統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "通報統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReportStatsResponse>> getStats() {
        ReportStatsResponse stats = reportActionService.getStats();
        return ResponseEntity.ok(ApiResponse.of(stats));
    }

    /**
     * ユーザーの違反履歴を取得する。
     */
    @GetMapping("/{id}/actions")
    @Operation(summary = "通報アクション履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReportActionResponse>>> getActions(@PathVariable Long id) {
        List<ReportActionResponse> actions = reportActionService.getActions(id);
        return ResponseEntity.ok(ApiResponse.of(actions));
    }

    /**
     * 通報を差し戻す。
     */
    @PatchMapping("/{id}/reopen")
    @Operation(summary = "通報差し戻し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "差し戻し成功")
    public ResponseEntity<ApiResponse<ReportActionResponse>> reopenReport(
            @PathVariable Long id,
            @RequestParam(required = false) @Size(max = 2000) String note) {
        ReportActionResponse response = reportActionService.reopenReport(id, note, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通報をエスカレーションする。
     */
    @PostMapping("/{id}/escalate")
    @Operation(summary = "通報エスカレーション")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エスカレーション成功")
    public ResponseEntity<ApiResponse<ReportActionResponse>> escalateReport(
            @PathVariable Long id,
            @Valid @RequestBody EscalateRequest request) {
        ReportActionResponse response = reportActionService.escalateReport(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * コンテンツを復元する。
     */
    @PatchMapping("/{id}/restore-content")
    @Operation(summary = "コンテンツ復元")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復元成功")
    public ResponseEntity<ApiResponse<ReportActionResponse>> restoreContent(
            @PathVariable Long id,
            @RequestParam(required = false) @Size(max = 2000) String note) {
        ReportActionResponse response = reportActionService.restoreContent(id, note, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ユーザーの通報権限を停止する。
     */
    @PatchMapping("/users/{userId}/restrict-reporting")
    @Operation(summary = "通報権限停止")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "停止成功")
    public ResponseEntity<ApiResponse<Void>> restrictReporting(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "true") boolean restricted) {
        // TODO: ユーザーの通報権限フラグを更新する実装
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * ユーザーの違反履歴を取得する。
     */
    @GetMapping("/users/{userId}/violation-history")
    @Operation(summary = "違反履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UserViolationHistoryResponse>> getViolationHistory(
            @PathVariable Long userId) {
        UserViolationHistoryResponse history = userViolationService.getViolationHistory(userId);
        return ResponseEntity.ok(ApiResponse.of(history));
    }
}
