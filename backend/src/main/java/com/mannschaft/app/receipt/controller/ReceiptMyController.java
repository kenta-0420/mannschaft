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
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;

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


    /**
     * 自分宛の領収書一覧を取得する。
     *
     * <p><b>認可の所在</b>: {@code ReceiptMyService.listMyReceipts}
     * （{@code receipt/service/ReceiptMyService.java:51}）が、リクエストの scopeId 有無に関わらず
     * 常に {@code recipientUserId=userId} を検索条件に AND 結合するため、他人の scopeId を渡しても
     * 自分宛以外の領収書は 1 件も返らない。</p>
     */
    @AuthorizedInService
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
                SecurityUtils.getCurrentUserId(), type, scopeId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 自分宛の領収書 PDF をダウンロードする。
     *
     * <p><b>認可の所在</b>: {@code ReceiptMyService.getMyReceiptPdf}
     * （{@code receipt/service/ReceiptMyService.java:92}）が
     * {@code receiptRepository.findByIdAndRecipientUserId(receiptId, userId)} で
     * 「当該領収書 ID かつ受取人本人」の複合条件で引き当て、不一致は
     * {@code RECEIPT_NOT_FOUND}（404）で秘匿する。PDF 生成は引き当てた Entity にのみ適用される。</p>
     */
    @AuthorizedInService
    @GetMapping("/{id}/pdf")
    @Operation(summary = "自分宛の領収書PDFダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ダウンロード成功")
    public ResponseEntity<byte[]> downloadMyReceiptPdf(@PathVariable Long id) {
        byte[] pdf = receiptMyService.getMyReceiptPdf(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt_" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * 年間サマリーを取得する。
     *
     * <p><b>認可の所在</b>: {@code ReceiptMyService.getAnnualSummary}
     * （{@code receipt/service/ReceiptMyService.java:113}）が、リクエストの scopeId 有無に関わらず
     * 常に {@code recipientUserId=userId} を検索条件に AND 結合するため、他人の scopeId を渡しても
     * 自分宛以外の領収書は集計対象に含まれない。</p>
     */
    @AuthorizedInService
    @GetMapping("/annual-summary")
    @Operation(summary = "年間サマリー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AnnualSummaryResponse>> getAnnualSummary(
            @RequestParam int year,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId) {
        ReceiptScopeType type = scopeType != null ? ReceiptScopeType.valueOf(scopeType.toUpperCase()) : null;
        AnnualSummaryResponse response = receiptMyService.getAnnualSummary(
                SecurityUtils.getCurrentUserId(), year, type, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
