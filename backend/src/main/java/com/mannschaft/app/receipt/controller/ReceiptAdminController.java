package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.BulkCreateReceiptRequest;
import com.mannschaft.app.receipt.dto.BulkResultResponse;
import com.mannschaft.app.receipt.dto.BulkVoidReceiptRequest;
import com.mannschaft.app.receipt.dto.BulkVoidResultResponse;
import com.mannschaft.app.receipt.dto.CreateReceiptRequest;
import com.mannschaft.app.receipt.dto.DescriptionSuggestionResponse;
import com.mannschaft.app.receipt.dto.DownloadZipRequest;
import com.mannschaft.app.receipt.dto.DownloadZipResponse;
import com.mannschaft.app.receipt.dto.ReceiptPreviewResponse;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.dto.ReceiptSummaryResponse;
import com.mannschaft.app.receipt.dto.ReissueReceiptRequest;
import com.mannschaft.app.receipt.dto.SendEmailRequest;
import com.mannschaft.app.receipt.dto.SendEmailResponse;
import com.mannschaft.app.receipt.dto.VoidReceiptRequest;
import com.mannschaft.app.receipt.service.ReceiptExportService;
import com.mannschaft.app.receipt.service.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 領収書管理コントローラー（ADMIN用）。領収書の発行・無効化・検索・エクスポートAPIを提供する。
 * <p>
 * エンドポイント数: 15
 * <ul>
 *   <li>POST   /api/v1/admin/receipts           — 領収書発行</li>
 *   <li>POST   /api/v1/admin/receipts/bulk       — 一括発行</li>
 *   <li>POST   /api/v1/admin/receipts/{id}/void  — 無効化</li>
 *   <li>POST   /api/v1/admin/receipts/{id}/reissue — 再発行プレビュー</li>
 *   <li>POST   /api/v1/admin/receipts/bulk-void  — 一括無効化</li>
 *   <li>PATCH  /api/v1/admin/receipts/{id}/approve — 下書き承認</li>
 *   <li>POST   /api/v1/admin/receipts/preview    — 発行前プレビュー</li>
 *   <li>GET    /api/v1/admin/receipts            — 一覧</li>
 *   <li>GET    /api/v1/admin/receipts/{id}       — 詳細</li>
 *   <li>GET    /api/v1/admin/receipts/{id}/pdf   — PDFダウンロード</li>
 *   <li>GET    /api/v1/admin/receipts/export     — CSVエクスポート</li>
 *   <li>POST   /api/v1/admin/receipts/download-zip — ZIP一括ダウンロード</li>
 *   <li>GET    /api/v1/admin/receipts/download-zip/{jobId} — ZIP状態確認</li>
 *   <li>GET    /api/v1/admin/receipts/description-suggestions — 但し書き候補</li>
 *   <li>POST   /api/v1/admin/receipts/{id}/send-email — メール送信</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/receipts")
@Tag(name = "領収書管理（ADMIN）", description = "F08.4 領収書の発行・無効化・検索・エクスポート")
@RequiredArgsConstructor
public class ReceiptAdminController {

    private final ReceiptService receiptService;
    private final ReceiptExportService exportService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 領収書を発行する。
     */
    @PostMapping
    @Operation(summary = "領収書発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<ReceiptResponse>> createReceipt(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody CreateReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptResponse response = receiptService.createReceipt(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 領収書を一括発行する。
     */
    @PostMapping("/bulk")
    @Operation(summary = "領収書一括発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括発行成功")
    public ResponseEntity<ApiResponse<BulkResultResponse>> bulkCreateReceipts(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody BulkCreateReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        BulkResultResponse response = receiptService.bulkCreateReceipts(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 領収書を無効化する。
     */
    @PostMapping("/{id}/void")
    @Operation(summary = "領収書無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "無効化成功")
    public ResponseEntity<ApiResponse<ReceiptResponse>> voidReceipt(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id,
            @Valid @RequestBody VoidReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptResponse response = receiptService.voidReceipt(type, scopeId, id, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 無効化済み領収書の再発行プレビューを取得する。
     */
    @PostMapping("/{id}/reissue")
    @Operation(summary = "再発行プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー取得成功")
    public ResponseEntity<ApiResponse<ReceiptPreviewResponse>> reissuePreview(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id,
            @Valid @RequestBody ReissueReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptPreviewResponse response = receiptService.reissuePreview(type, scopeId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 領収書を一括無効化する。
     */
    @PostMapping("/bulk-void")
    @Operation(summary = "領収書一括無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括無効化成功")
    public ResponseEntity<ApiResponse<BulkVoidResultResponse>> bulkVoidReceipts(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody BulkVoidReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        BulkVoidResultResponse response = receiptService.bulkVoidReceipts(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 下書き領収書を承認する（DRAFT → ISSUED）。
     */
    @PatchMapping("/{id}/approve")
    @Operation(summary = "下書き領収書の承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<ReceiptResponse>> approveReceipt(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptResponse response = receiptService.approveReceipt(type, scopeId, id, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 発行前プレビューを取得する。
     */
    @PostMapping("/preview")
    @Operation(summary = "発行前プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー取得成功")
    public ResponseEntity<ApiResponse<ReceiptPreviewResponse>> previewReceipt(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody CreateReceiptRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptPreviewResponse response = receiptService.previewReceipt(type, scopeId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 発行済み領収書一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "領収書一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ReceiptSummaryResponse>> listReceipts(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        PagedResponse<ReceiptSummaryResponse> response = receiptService.listReceipts(type, scopeId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 領収書詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "領収書詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReceiptResponse>> getReceipt(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptResponse response = receiptService.getReceipt(type, scopeId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 領収書 PDF をダウンロードする。
     */
    @GetMapping("/{id}/pdf")
    @Operation(summary = "領収書PDFダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ダウンロード成功")
    public ResponseEntity<byte[]> downloadPdf(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        byte[] pdf = receiptService.getReceiptPdf(type, scopeId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt_" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * 領収書一覧を CSV エクスポートする。
     */
    @GetMapping("/export")
    @Operation(summary = "領収書CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "false") boolean includeVoided) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        byte[] csv = exportService.exportCsv(type, scopeId, year, issuedFrom, issuedTo, includeVoided);
        String filename = "receipts_" + scopeType + "_" + scopeId +
                (year != null ? "_" + year : "") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * 領収書 PDF の一括ダウンロード（ZIP）を開始する。
     */
    @PostMapping("/download-zip")
    @Operation(summary = "ZIP一括ダウンロード開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "ジョブ作成成功")
    public ResponseEntity<ApiResponse<DownloadZipResponse>> createZipDownload(
            @Valid @RequestBody DownloadZipRequest request) {
        DownloadZipResponse response = exportService.createZipJob(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    /**
     * ZIP ダウンロードジョブの状態を確認する。
     */
    @GetMapping("/download-zip/{jobId}")
    @Operation(summary = "ZIPダウンロード状態確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DownloadZipResponse>> getZipDownloadStatus(
            @PathVariable String jobId) {
        DownloadZipResponse response = exportService.getZipJob(jobId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 但し書きの自動生成候補を取得する。
     */
    @GetMapping("/description-suggestions")
    @Operation(summary = "但し書き候補取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DescriptionSuggestionResponse>> getDescriptionSuggestions(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) Long memberPaymentId) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        DescriptionSuggestionResponse response = exportService.getDescriptionSuggestions(type, scopeId, memberPaymentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 発行済み領収書の PDF を受領者にメール送信する。
     */
    @PostMapping("/{id}/send-email")
    @Operation(summary = "領収書メール送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "送信キュー追加成功")
    public ResponseEntity<ApiResponse<SendEmailResponse>> sendEmail(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id,
            @Valid @RequestBody SendEmailRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        SendEmailResponse response = receiptService.sendEmail(type, scopeId, id, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }
}
