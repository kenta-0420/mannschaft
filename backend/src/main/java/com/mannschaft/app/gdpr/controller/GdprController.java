package com.mannschaft.app.gdpr.controller;

import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gdpr.dto.DataExportRequest;
import com.mannschaft.app.gdpr.dto.DataExportResponse;
import com.mannschaft.app.gdpr.dto.DeletionPreviewResponse;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPRコントローラー。データエクスポート・削除プレビューAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "GDPR/個人情報管理", description = "F12.3 データエクスポート・削除プレビュー")
@RequiredArgsConstructor
public class GdprController {

    private final DataExportService dataExportService;
    private final ChartRecordRepository chartRecordRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberPaymentRepository memberPaymentRepository;

    /**
     * POST /api/v1/account/data-export
     * データエクスポートをリクエストする。
     * パスワードユーザー: password再認証 + 非同期処理開始
     * OAuthユーザー: OTPをメール送信して202を返す
     */
    @PostMapping("/data-export")
    @Operation(summary = "データエクスポートリクエスト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "エクスポートリクエスト受付")
    public ResponseEntity<ApiResponse<DataExportResponse>> requestExport(
            @Valid @RequestBody DataExportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.requestExport(userId, request.getCategories());
        dataExportService.processExportAsync(entity.getId(), userId, request.getCategories());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(toResponse(entity)));
    }

    /**
     * GET /api/v1/account/data-export/status
     * エクスポートの現在ステータスを取得する。
     */
    @GetMapping("/data-export/status")
    @Operation(summary = "エクスポートステータス取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DataExportResponse>> getExportStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.getExportStatus(userId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    /**
     * GET /api/v1/account/data-export/download
     * 完了済みZIPのダウンロードURLを返す。
     */
    @GetMapping("/data-export/download")
    @Operation(summary = "エクスポートダウンロードURL取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL取得成功")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDownloadUrl() {
        Long userId = SecurityUtils.getCurrentUserId();
        String url = dataExportService.getDownloadUrl(userId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("downloadUrl", url)));
    }

    /**
     * GET /api/v1/account/deletion-preview
     * 退会時に削除/匿名化されるデータの件数サマリーを返す。
     * 再認証不要（読み取り専用）。
     */
    @GetMapping("/deletion-preview")
    @Operation(summary = "退会時削除データプレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー取得成功")
    public ResponseEntity<ApiResponse<DeletionPreviewResponse>> getDeletionPreview() {
        Long userId = SecurityUtils.getCurrentUserId();
        DeletionPreviewResponse response = buildDeletionPreview(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    /**
     * DataExportEntity を DataExportResponse に変換する。
     */
    private DataExportResponse toResponse(DataExportEntity entity) {
        return DataExportResponse.builder()
                .exportId(entity.getId())
                .status(entity.getStatus())
                .progressPercent(entity.getProgressPercent())
                .currentStep(entity.getProgressStep())
                .fileSizeBytes("COMPLETED".equals(entity.getStatus()) ? entity.getFileSize() : null)
                .expiresAt("COMPLETED".equals(entity.getStatus()) ? entity.getExpiresAt() : null)
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }

    /**
     * 各カテゴリの件数を取得して DeletionPreviewResponse を構築する。
     */
    private DeletionPreviewResponse buildDeletionPreview(Long userId) {
        long chartCount = chartRecordRepository.countByCustomerUserId(userId);
        long chatCount = chatMessageRepository.countBySenderId(userId);
        long paymentCount = memberPaymentRepository.findByUserId(userId).size();

        Map<String, Long> dataSummary = new LinkedHashMap<>();
        dataSummary.put("charts", chartCount);
        dataSummary.put("chatMessages", chatCount);
        dataSummary.put("payments", paymentCount);

        List<DeletionPreviewResponse.AnonymizedItem> anonymized = new ArrayList<>();
        if (chartCount > 0) {
            anonymized.add(DeletionPreviewResponse.AnonymizedItem.builder()
                    .category("charts")
                    .count(chartCount)
                    .note("カルテ情報は匿名化されてスタッフ側に残ります")
                    .build());
        }
        if (chatCount > 0) {
            anonymized.add(DeletionPreviewResponse.AnonymizedItem.builder()
                    .category("chatMessages")
                    .count(chatCount)
                    .note("チャットメッセージは論理削除されます")
                    .build());
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("退会後30日以内であれば取り消しが可能です");
        warnings.add("ダウンロード済みのデータエクスポートは引き続き有効です");

        return DeletionPreviewResponse.builder()
                .retentionDays(30)
                .dataSummary(dataSummary)
                .anonymized(anonymized)
                .warnings(warnings)
                .build();
    }
}
