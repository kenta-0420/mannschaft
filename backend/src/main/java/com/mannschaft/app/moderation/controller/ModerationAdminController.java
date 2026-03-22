package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.service.ContentReportService;
import com.mannschaft.app.moderation.service.ReportActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * モデレーション管理者コントローラー。通報レビュー・一覧取得APIを管理者向けに提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/moderation/reports")
@Tag(name = "モデレーション管理", description = "F04.5 管理者向け通報レビュー")
@RequiredArgsConstructor
public class ModerationAdminController {

    private final ContentReportService reportService;
    private final ReportActionService actionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 未対応の通報一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "未対応通報一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReportResponse>>> getPendingReports(
            @RequestParam(defaultValue = "20") int size) {
        List<ReportResponse> reports = reportService.getPendingReports(size);
        return ResponseEntity.ok(ApiResponse.of(reports));
    }

    /**
     * 通報詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "通報詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReportResponse>> getReport(@PathVariable Long id) {
        ReportResponse response = reportService.getReport(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通報のレビューを開始する（PENDING → REVIEWING）。
     */
    @PatchMapping("/{id}/review")
    @Operation(summary = "通報レビュー開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "レビュー開始成功")
    public ResponseEntity<ApiResponse<ReportResponse>> startReview(@PathVariable Long id) {
        actionService.startReview(id, getCurrentUserId());
        ReportResponse response = reportService.getReport(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
