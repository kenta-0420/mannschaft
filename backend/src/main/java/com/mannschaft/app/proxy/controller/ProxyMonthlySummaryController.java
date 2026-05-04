package com.mannschaft.app.proxy.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.proxy.service.ProxyMonthlySummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Map;

/**
 * 代理入力月次サマリダウンロードAPIコントローラー（F14.1 Phase 13-β）。
 * ADMIN以上が非デジタル住民向け月次サマリPDFの presigned URL を取得する。
 * proxy_input_records に organizationId が存在しないため、
 * subjectUserId 単位のエンドポイント設計を採用する。
 */
@Tag(name = "代理入力", description = "F14.1 非デジタル住民対応・代理入力 月次サマリ")
@RestController
@RequestMapping("/api/v1/proxy-input/monthly-summaries")
@RequiredArgsConstructor
public class ProxyMonthlySummaryController {

    private final ProxyMonthlySummaryService summaryService;

    /**
     * 月次サマリPDFのダウンロードURL（presigned、5分TTL）を取得する。
     *
     * <p>GET /api/v1/proxy-input/monthly-summaries/{year}/{month}/{subjectUserId}/download-url</p>
     *
     * <p>権限: ADMIN以上（SecurityConfig で制限）。</p>
     *
     * @param year          対象年（例: 2026）
     * @param month         対象月（例: 4）
     * @param subjectUserId 本人ユーザーID
     * @return {@code { "downloadUrl": "https://..." }}
     */
    @Operation(summary = "代理入力月次サマリPDF ダウンロードURL取得")
    @GetMapping("/{year}/{month}/{subjectUserId}/download-url")
    public ApiResponse<Map<String, String>> getDownloadUrl(
            @PathVariable int year,
            @PathVariable int month,
            @PathVariable Long subjectUserId) {

        YearMonth targetMonth = YearMonth.of(year, month);
        String url = summaryService.getDownloadUrl(subjectUserId, targetMonth);
        return ApiResponse.of(Map.of("downloadUrl", url));
    }
}
