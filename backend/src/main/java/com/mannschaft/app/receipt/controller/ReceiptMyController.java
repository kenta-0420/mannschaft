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
import com.mannschaft.app.common.security.SelfScopedEndpoint;

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
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code receiptMyService.listMyReceipts}
     * は {@code SecurityUtils.getCurrentUserId()} を recipientUserId として検索条件に必ず束縛し、
     * クエリの {@code scopeId} は自分宛の結果をさらに絞り込むだけで、他人宛の領収書へ到達する経路が
     * 構造的に無い（ReceiptMyController#listMyReceipts）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "receiptMyService.listMyReceipts(userId, ...) は SecurityUtils.getCurrentUserId() を"
                    + "recipientUserId として必ず束縛する（ReceiptMyController#listMyReceipts）")
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
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code receiptMyService.getMyReceiptPdf}
     * が {@code receiptRepository.findByIdAndRecipientUserId(id, userId)} で「当該領収書 ID かつ
     * 受取人本人」の複合条件で引き当てる。受取人以外の id は不存在と区別せず
     * {@code RECEIPT_NOT_FOUND}（404）で秘匿する（ReceiptMyController#downloadMyReceiptPdf）。
     * 認可根治戦役 Wave6 監査済。</p>
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
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code receiptMyService.getAnnualSummary}
     * は {@code SecurityUtils.getCurrentUserId()} を recipientUserId として検索条件に必ず束縛し、
     * クエリの {@code scopeId} は自分宛の集計をさらに絞り込むだけで、他人宛の領収書集計へ到達する経路が
     * 構造的に無い（ReceiptMyController#getAnnualSummary）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "receiptMyService.getAnnualSummary(userId, ...) は SecurityUtils.getCurrentUserId() を"
                    + "recipientUserId として必ず束縛する（ReceiptMyController#getAnnualSummary）")
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
