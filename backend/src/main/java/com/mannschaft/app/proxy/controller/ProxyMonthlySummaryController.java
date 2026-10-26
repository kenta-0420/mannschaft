package com.mannschaft.app.proxy.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 * 本人または管理者が非デジタル住民向け月次サマリPDFの presigned URL を取得する。
 * proxy_input_records に organizationId が存在しないため、
 * subjectUserId 単位のエンドポイント設計を採用する。
 *
 * <p><b>認可</b>: {@code SecurityConfig} には {@code /api/v1/proxy-input/**} の
 * requestMatcher が存在せず、パス単位認可は掛かっていない。認可は
 * {@code ProxyMonthlySummaryService} 側で「本人 / SYSTEM_ADMIN / 対象住民の同意書が属する
 * 組合の ADMIN・DEPUTY_ADMIN」として実施する（同ドメインの
 * {@code ProxyInputConsentController#generateScanDownloadUrl} と同じ作法）。</p>
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
     * <p>権限: 本人 or SYSTEM_ADMIN or 対象住民の同意書が属する組合の ADMIN/DEPUTY_ADMIN
     * （{@code ProxyMonthlySummaryService} で検証。SecurityConfig によるパス単位認可は存在しない）。</p>
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
        String url = summaryService.getDownloadUrl(
                SecurityUtils.getCurrentUserId(), subjectUserId, targetMonth);
        return ApiResponse.of(Map.of("downloadUrl", url));
    }
}
