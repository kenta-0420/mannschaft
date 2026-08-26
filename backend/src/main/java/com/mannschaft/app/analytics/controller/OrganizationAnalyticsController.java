package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.TeamOrgAnalyticsErrorCode;
import com.mannschaft.app.analytics.dto.PageViewAnalyticsResponse;
import com.mannschaft.app.analytics.service.PageViewAnalyticsAccessGuard;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.AnalyticsResult;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.ContentStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.DailyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.MonthlyStat;
import com.mannschaft.app.analytics.service.PageViewAnalyticsService.SummaryStat;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 組織アクセス解析コントローラー（F10.8）。
 *
 * <p>{@code GET /api/v1/organizations/{slug}/analytics} — 指定組織のアクセス解析を返す。
 * チーム版（{@link TeamAnalyticsController}）と同一構造・同一権限規則（AC-17）。</p>
 *
 * <p>slug 解決: {@link OrganizationService#resolveOrgId(String)} を使用
 * （設計書 §3.0 確定メソッド名）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/organizations/{slug}")
@Tag(name = "組織アクセス解析")
@RequiredArgsConstructor
public class OrganizationAnalyticsController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final OrganizationService organizationService;
    private final PageViewAnalyticsAccessGuard accessGuard;
    private final PageViewAnalyticsService analyticsService;

    /**
     * 組織のアクセス解析を取得する（AC-17）。
     *
     * @param slug     組織 slug
     * @param dateFrom 集計開始日（省略可・"YYYY-MM-DD"）
     * @param dateTo   集計終了日（省略可・"YYYY-MM-DD"）
     * @return 200 + {@link PageViewAnalyticsResponse}
     */
    @GetMapping("/analytics")
    @Operation(summary = "組織アクセス解析取得", description = "組織の PV 集計を返す。メンバーのみ閲覧可。")
    public ResponseEntity<ApiResponse<PageViewAnalyticsResponse>> getAnalytics(
            @PathVariable String slug,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        // slug → 数値 ID 解決（存在しない slug は OrganizationService が 404 を投げる）
        Long orgId = organizationService.resolveOrgId(slug);

        // 認可ガード（非メンバー・未認証は TEAMANALYTICS_001 / 404）
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        accessGuard.requireScopeMember(userId, PageViewScopeType.ORGANIZATION, orgId);

        // 日付範囲検証（AC-11）
        validateDateRange(dateFrom, dateTo);

        // 集計取得
        AnalyticsResult result = analyticsService.getAnalytics(PageViewScopeType.ORGANIZATION, orgId, dateFrom, dateTo);

        return ResponseEntity.ok(ApiResponse.of(toResponse(result)));
    }

    // ─── 内部ヘルパー ────────────────────────────────────────────────

    /**
     * dateFrom > dateTo の場合は {@code TEAMANALYTICS_002} (400) を投げる（AC-11）。
     */
    private void validateDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BusinessException(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_002);
        }
    }

    /**
     * Service のビューモデルから FE 契約 DTO へ変換する。
     * topContent は第 2 弾で実データ（人気コンテンツランキング）を返す（AC-P2-8）。
     * FE 型は全フィールド非 null 契約のため、防御的に文字列は空文字へフォールバックする。
     */
    private PageViewAnalyticsResponse toResponse(AnalyticsResult result) {
        SummaryStat s = result.summary();
        List<DailyStat> daily = result.daily();
        List<MonthlyStat> monthly = result.monthly();
        List<ContentStat> topContent = result.topContent();

        return PageViewAnalyticsResponse.builder()
                .summary(PageViewAnalyticsResponse.SummaryDto.builder()
                        .totalViews(s.totalViews())
                        .uniqueVisitors(s.uniqueVisitors())
                        .memberViews(s.memberViews())
                        .guestViews(s.guestViews())
                        .build())
                .daily(daily.stream()
                        .map(d -> PageViewAnalyticsResponse.DailyDto.builder()
                                .date(d.date().format(DATE_FORMATTER))
                                .views(d.views())
                                .uniqueVisitors(d.uniqueVisitors())
                                .build())
                        .toList())
                .monthly(monthly.stream()
                        .map(m -> PageViewAnalyticsResponse.MonthlyDto.builder()
                                .month(m.month().format(MONTH_FORMATTER))
                                .views(m.views())
                                .uniqueVisitors(m.uniqueVisitors())
                                .build())
                        .toList())
                .topContent(topContent.stream()
                        .map(c -> PageViewAnalyticsResponse.ContentRankingDto.builder()
                                .contentType(Objects.requireNonNullElse(c.contentType(), ""))
                                .contentId(c.contentId())
                                .title(Objects.requireNonNullElse(c.title(), ""))
                                .url(Objects.requireNonNullElse(c.url(), ""))
                                .views(c.views())
                                .uniqueVisitors(c.uniqueVisitors())
                                .build())
                        .toList())
                .build();
    }
}
