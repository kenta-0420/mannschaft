package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewDailyStatsEntity;
import com.mannschaft.app.analytics.repository.PageViewDailyStatsRepository;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * ページビュー集計取得サービス（F10.8 アクセス解析・GET のデータ源）。
 *
 * <p>認可済みの scope（team / organization）について、指定期間の summary / daily / monthly / topContent を
 * 組み立てて返す（設計書 §5.3）。topContent（人気コンテンツランキング）は<b>第2弾で実データ</b>を
 * 生ログ {@code page_view_logs} の {@code (content_type, content_id)} 集計から算出する。
 * 認可は本サービスに埋めず、Controller 入口で {@link PageViewAnalyticsAccessGuard} が行う
 * （共有メソッドにガードを付けない・既存戒め）。</p>
 *
 * <h2>uniqueVisitors の 2 クエリ構成（設計書 §5.3）</h2>
 * <p>{@code summary.uniqueVisitors} / {@code monthly[].uniqueVisitors} は日次合算では重複カウントになる。
 * よって:</p>
 * <ul>
 *   <li>daily / views / member / guest は {@code page_view_daily_stats}（日次集計）から正確に取る</li>
 *   <li>summary の総 PV / member / guest / uniqueVisitors、および monthly の uniqueVisitors は
 *       <b>保持期間（13 ヶ月）内なら {@code page_view_logs} から直接 DISTINCT で正確値</b>を取る
 *       （生ログが空でも {@code COUNT} は 0 を返し例外にならない・AC-20）</li>
 * </ul>
 *
 * <p>期間指定（dateFrom / dateTo）省略時は全期間（設計書 AC-12）。第1弾の保持期間は 13 ヶ月のため、
 * 全期間指定でもほぼ常に生ログ直接ルートで正確値になる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PageViewAnalyticsService {

    /** 生ログ保持期間（月）。これ以内なら生ログ直接 DISTINCT で正確値を取れる（設計書 §9）。 */
    private static final int LOG_RETENTION_MONTHS = 13;

    /** 人気コンテンツランキングの取得上限件数（第2弾・設計書 §5.3）。 */
    private static final int TOP_CONTENT_LIMIT = 10;

    /** 全期間指定の下限日付（保持期間内で十分過去。生ログ直接集計の from に使う）。 */
    private static final LocalDate ALL_TIME_FROM = LocalDate.of(1970, 1, 1);

    private final PageViewDailyStatsRepository dailyStatsRepository;
    private final PageViewLogRepository logRepository;

    /**
     * 指定スコープ・期間のアクセス解析を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（認可済み・数値）
     * @param dateFrom  集計開始日（{@code null} = 全期間）
     * @param dateTo    集計終了日（{@code null} = 全期間）
     * @return summary / daily / monthly を含む集計結果（topContent は Controller が空配列にする）
     */
    public AnalyticsResult getAnalytics(
            PageViewScopeType scopeType, Long scopeId, LocalDate dateFrom, LocalDate dateTo) {

        LocalDate from = dateFrom != null ? dateFrom : ALL_TIME_FROM;
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();

        // daily は日次集計テーブルから（views/member/guest は正確値）
        List<PageViewDailyStatsEntity> dailyRows =
                dailyStatsRepository.findByScopeTypeAndScopeIdAndDateBetweenOrderByDateAsc(
                        scopeType, scopeId, from, to);
        List<DailyStat> daily = new ArrayList<>(dailyRows.size());
        for (PageViewDailyStatsEntity row : dailyRows) {
            daily.add(new DailyStat(
                    row.getDate(),
                    row.getTotalViews(),
                    row.getUniqueVisitors()));
        }

        // 保持期間（13ヶ月）判定。保持期間より古いパーティションは DROP 済みなので、
        // 全期間指定（ALL_TIME_FROM）や保持期間内の指定は生ログ直接 DISTINCT で正確値を取れる。
        // 保持期間より前を明示指定した場合のみ、アーカイブ済み区間を含むため日次合算の近似にフォールバックする。
        LocalDate retentionFloor = LocalDate.now().minusMonths(LOG_RETENTION_MONTHS);
        boolean withinRetention = from.equals(ALL_TIME_FROM) || !from.isBefore(retentionFloor);

        // 生ログ直接集計用の日時境界（[logFrom 00:00, toExclusive 00:00) の半開区間）。
        // 全期間指定でも生ログは保持期間内にしか存在しないため、下限を保持期間床にクランプしてよい。
        LocalDate effectiveLogFrom = from.isBefore(retentionFloor) ? retentionFloor : from;
        LocalDateTime logFrom = effectiveLogFrom.atStartOfDay();
        LocalDateTime logToExclusive = to.plusDays(1).atStartOfDay();
        String scopeTypeName = scopeType.name();

        SummaryStat summary = buildSummary(
                scopeType, scopeId, scopeTypeName, from, to,
                logFrom, logToExclusive, withinRetention);

        List<MonthlyStat> monthly = buildMonthly(
                scopeType, scopeId, scopeTypeName, from, to,
                logFrom, logToExclusive, withinRetention);

        List<ContentStat> topContent = buildTopContent(scopeTypeName, scopeId, logFrom, logToExclusive);

        return new AnalyticsResult(summary, daily, monthly, topContent);
    }

    /**
     * 人気コンテンツランキング（topContent）を組み立てる（第2弾）。
     *
     * <p>保持期間内の生ログ境界 {@code [logFrom, logToExclusive)} を {@code (content_type, content_id)}
     * 別に集計し、views 降順の上位 {@link #TOP_CONTENT_LIMIT} 件を返す。生ログが空でも
     * {@code findTopContent} は空リストを返し例外にならない（AC-P2-4）。</p>
     */
    private List<ContentStat> buildTopContent(
            String scopeTypeName, Long scopeId, LocalDateTime logFrom, LocalDateTime logToExclusive) {
        List<PageViewLogRepository.ContentRankingProjection> rows =
                logRepository.findTopContent(scopeTypeName, scopeId, logFrom, logToExclusive, TOP_CONTENT_LIMIT);
        List<ContentStat> topContent = new ArrayList<>(rows.size());
        for (PageViewLogRepository.ContentRankingProjection row : rows) {
            topContent.add(new ContentStat(
                    row.getContentType(),
                    row.getContentId(),
                    row.getTitle(),
                    row.getUrl(),
                    row.getViews(),
                    row.getUniqueVisitors()));
        }
        return topContent;
    }

    /**
     * summary を組み立てる。保持期間内なら生ログ直接 DISTINCT で正確値、期間外なら日次合算の近似値。
     */
    private SummaryStat buildSummary(
            PageViewScopeType scopeType, Long scopeId, String scopeTypeName,
            LocalDate from, LocalDate to,
            LocalDateTime logFrom, LocalDateTime logToExclusive, boolean withinRetention) {
        if (withinRetention) {
            long total = logRepository.countTotalViews(scopeTypeName, scopeId, logFrom, logToExclusive);
            long member = logRepository.countMemberViews(scopeTypeName, scopeId, logFrom, logToExclusive);
            long guest = logRepository.countGuestViews(scopeTypeName, scopeId, logFrom, logToExclusive);
            long unique = logRepository.countUniqueVisitors(scopeTypeName, scopeId, logFrom, logToExclusive);
            return new SummaryStat(total, unique, member, guest);
        }
        // 保持期間外（アーカイブ済み）: 日次集計の合算にフォールバック（uniqueVisitors は上振れ近似）
        PageViewDailyStatsRepository.PeriodSummary sum =
                dailyStatsRepository.sumForPeriod(scopeType, scopeId, from, to);
        if (sum == null) {
            return new SummaryStat(0, 0, 0, 0);
        }
        return new SummaryStat(
                sum.getTotalViews(), sum.getUniqueVisitors(), sum.getMemberViews(), sum.getGuestViews());
    }

    /**
     * monthly を組み立てる。views/member/guest は日次集計の月合算（正確）、uniqueVisitors のみ
     * 保持期間内なら生ログ直接 DISTINCT で正確値に差し替える。
     */
    private List<MonthlyStat> buildMonthly(
            PageViewScopeType scopeType, Long scopeId, String scopeTypeName,
            LocalDate from, LocalDate to,
            LocalDateTime logFrom, LocalDateTime logToExclusive, boolean withinRetention) {

        List<PageViewDailyStatsRepository.MonthlyAggregate> rows =
                dailyStatsRepository.aggregateMonthlyForPeriod(scopeType, scopeId, from, to);

        List<MonthlyStat> monthly = new ArrayList<>(rows.size());
        for (PageViewDailyStatsRepository.MonthlyAggregate row : rows) {
            YearMonth ym = YearMonth.of(row.getYear(), row.getMonth());
            long unique = row.getUniqueVisitors();
            if (withinRetention) {
                // 月境界の生ログ直接 DISTINCT で正確値に差し替え
                LocalDateTime monthFrom = ym.atDay(1).atStartOfDay();
                LocalDateTime monthToExclusive = ym.plusMonths(1).atDay(1).atStartOfDay();
                unique = logRepository.countUniqueVisitors(
                        scopeTypeName, scopeId, monthFrom, monthToExclusive);
            }
            monthly.add(new MonthlyStat(ym, row.getTotalViews(), unique));
        }
        return monthly;
    }

    /**
     * 集計結果（Service 層のビューモデル）。Controller が FE 契約 DTO
     * （{@code AnalyticsResponse}）へマップする。全フィールド非 null。
     *
     * @param summary    サマリ
     * @param daily      日次配列（空配列可・非 null）
     * @param monthly    月次配列（空配列可・非 null）
     * @param topContent 人気コンテンツランキング（空配列可・非 null・第2弾で実データ）
     */
    public record AnalyticsResult(
            SummaryStat summary,
            List<DailyStat> daily,
            List<MonthlyStat> monthly,
            List<ContentStat> topContent) {
    }

    /**
     * 人気コンテンツランキング 1 件（Controller が {@code ContentRankingDto} へマップする）。全フィールド非 null。
     *
     * @param contentType    閲覧対象種別（enum 名の文字列）
     * @param contentId      閲覧対象 ID
     * @param title          代表タイトル（最新 viewed_at の値）
     * @param url            代表 URL（最新 viewed_at の値・アプリ内相対パス）
     * @param views          期間内の総 PV
     * @param uniqueVisitors 期間内のユニーク訪問者数
     */
    public record ContentStat(
            String contentType,
            long contentId,
            String title,
            String url,
            long views,
            long uniqueVisitors) {
    }

    /**
     * サマリ（総 PV / ユニーク訪問者 / メンバー PV / ゲスト PV）。
     */
    public record SummaryStat(
            long totalViews,
            long uniqueVisitors,
            long memberViews,
            long guestViews) {
    }

    /**
     * 日次値（日付 / PV / ユニーク訪問者）。
     */
    public record DailyStat(
            LocalDate date,
            long views,
            long uniqueVisitors) {
    }

    /**
     * 月次値（年月 / PV / ユニーク訪問者）。Controller が {@code YYYY-MM} 文字列に整形する。
     */
    public record MonthlyStat(
            YearMonth month,
            long views,
            long uniqueVisitors) {
    }
}
