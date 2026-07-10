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
import com.mannschaft.app.team.service.TeamService;
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
 * チームアクセス解析コントローラー（F10.8）。
 *
 * <p>{@code GET /api/v1/teams/{slug}/analytics} — 指定チームのアクセス解析を返す。</p>
 *
 * <p>認可: 当該チームのメンバー（または SYSTEM_ADMIN）のみ閲覧可。
 * 非メンバー・未認証は {@code 404}（{@code TEAMANALYTICS_001}）で秘匿（IDOR 隠蔽・AC-09）。</p>
 *
 * <p>日付範囲: {@code dateFrom > dateTo} は {@code 400}（{@code TEAMANALYTICS_002}）を投げる（AC-11）。
 * 省略時は全期間集計（AC-12）。</p>
 *
 * <p>topContent（人気コンテンツランキング）は第 2 弾で実データを返す（AC-P2-8）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{slug}")
@Tag(name = "チームアクセス解析")
@RequiredArgsConstructor
public class TeamAnalyticsController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TeamService teamService;
    private final PageViewAnalyticsAccessGuard accessGuard;
    private final PageViewAnalyticsService analyticsService;

    /**
     * チームのアクセス解析を取得する。
     *
     * @param slug     チーム slug
     * @param dateFrom 集計開始日（省略可・"YYYY-MM-DD"）
     * @param dateTo   集計終了日（省略可・"YYYY-MM-DD"）
     * @return 200 + {@link PageViewAnalyticsResponse}
     */
    @GetMapping("/analytics")
    @Operation(summary = "チームアクセス解析取得", description = "チームの PV 集計を返す。メンバーのみ閲覧可。")
    public ResponseEntity<ApiResponse<PageViewAnalyticsResponse>> getAnalytics(
            @PathVariable String slug,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        // slug → 数値 ID 解決（存在しない slug は TeamService が TEAM_001 / 404 を投げる）
        Long teamId = teamService.resolveTeamId(slug);

        // 認可ガード（非メンバー・未認証は TEAMANALYTICS_001 / 404）
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        accessGuard.requireScopeMember(userId, PageViewScopeType.TEAM, teamId);

        // 日付範囲検証（AC-11）
        validateDateRange(dateFrom, dateTo);

        // 集計取得
        AnalyticsResult result = analyticsService.getAnalytics(PageViewScopeType.TEAM, teamId, dateFrom, dateTo);

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
