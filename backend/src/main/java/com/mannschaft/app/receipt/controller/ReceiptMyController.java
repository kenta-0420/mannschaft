package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.AnnualSummaryResponse;
import com.mannschaft.app.receipt.dto.MyReceiptResponse;
import com.mannschaft.app.receipt.service.ReceiptMyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 領収書マイページコントローラー。メンバー自身宛の領収書取得APIを提供する。
 * <p>
 * エンドポイント数: 3
 * <ul>
 *   <li>GET /api/v1/my/receipts                 — 自分宛の領収書一覧</li>
 *   <li>GET /api/v1/my/receipts/{id}/pdf        — 自分宛の領収書PDFダウンロード</li>
 *   <li>GET /api/v1/my/receipts/annual-summary  — 年間サマリー</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/my/receipts")
@Tag(name = "領収書マイページ", description = "F08.4 自分宛の領収書取得")
@RequiredArgsConstructor
public class ReceiptMyController {

    private final ReceiptMyService receiptMyService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分宛の領収書一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "自分宛の領収書一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MyReceiptResponse>> listMyReceipts(
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceiptScopeType type = scopeType != null ? ReceiptScopeType.valueOf(scopeType.toUpperCase()) : null;
        PagedResponse<MyReceiptResponse> response = receiptMyService.listMyReceipts(
                getCurrentUserId(), type, scopeId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 自分宛の領収書 PDF をダウンロードする。
     */
    @GetMapping("/{id}/pdf")
    @Operation(summary = "自分宛の領収書PDFダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ダウンロード成功")
    public ResponseEntity<byte[]> downloadMyReceiptPdf(@PathVariable Long id) {
        byte[] pdf = receiptMyService.getMyReceiptPdf(getCurrentUserId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt_" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * 年間サマリーを取得する。
     */
    @GetMapping("/annual-summary")
    @Operation(summary = "年間サマリー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AnnualSummaryResponse>> getAnnualSummary(
            @RequestParam int year,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId) {
        ReceiptScopeType type = scopeType != null ? ReceiptScopeType.valueOf(scopeType.toUpperCase()) : null;
        AnnualSummaryResponse response = receiptMyService.getAnnualSummary(
                getCurrentUserId(), year, type, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
