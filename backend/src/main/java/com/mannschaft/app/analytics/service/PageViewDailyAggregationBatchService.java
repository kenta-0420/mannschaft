package com.mannschaft.app.analytics.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewDailyStatsEntity;
import com.mannschaft.app.analytics.repository.PageViewDailyStatsRepository;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ページビュー日次集計バッチ（F10.8 アクセス解析）。
 *
 * <p>毎日 AM 2:00 (JST) に前日分の生ログ {@code page_view_logs} を scope 単位で集計し、
 * {@code page_view_daily_stats} に「1 スコープ 1 日 1 行」で書き込む（手本 F10.4
 * {@code DailyAggregationBatchService} / {@code analytics-daily-aggregation}）。</p>
 *
 * <h2>冪等性（AC-18）</h2>
 * <p>対象日の各 scope について {@code deleteByScopeTypeAndScopeIdAndDate} で既存行を削除してから
 * 再 INSERT する。同一日で 2 回実行しても行数・値が変わらない。生ログが 1 件も無い scope は
 * 集計結果に現れないため行は作られない（AC-20・summary は生ログ直接集計で 0 を返す）。</p>
 *
 * <h2>ユニーク訪問者の重複除去（設計書 §3.4）</h2>
 * <p>{@code unique_visitors} は生ログ側の
 * {@code COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id)))} で数える
 * （{@link PageViewLogRepository#aggregateByScopeForPeriod} が算出）。プレフィクスで型を揃え
 * 名前空間衝突を防ぐ。ゲストが同一 cookie で同日 3 回閲覧しても unique は 1（AC-14）。</p>
 *
 * <h2>日付境界（JST・設計書 §5.5）</h2>
 * <p>前日 = {@code LocalDate.now(Asia/Tokyo).minusDays(1)}。集計対象の生ログ範囲は
 * その JST 日付の {@code [00:00, 翌日 00:00)} 半開区間。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageViewDailyAggregationBatchService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final PageViewLogRepository logRepository;
    private final PageViewDailyStatsRepository dailyStatsRepository;

    /**
     * 日次集計バッチ本体。前日（JST）を集計する。ShedLock 排他（最大 30 分）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "execute は常に前日のみを集計し、対象日を指定して再実行できる運用経路が存在しない（aggregateForDate は public だが呼び手がゼロで、AnalyticsBackfillService が面倒を見るのは DailyAggregation と MonthlyCohort だけ。BatchEndpoint も引数なしの execute を呼ぶ）。よって 2 日以上止めると、その期間の日次集計は二度と作れず恒久的に欠測する。集計は内部処理のみで外部送信を伴わず、閉栓中に空回りしても害が無い")
    @BatchEndpoint(
            name = "analytics-pageview-daily-aggregation",
            description = "前日分のページビュー生ログを scope 単位で集計し page_view_daily_stats に毎日 02:00 に書き込む")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "analyticsPageViewDailyAggregation", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void execute() {
        LocalDate yesterday = LocalDate.now(JST).minusDays(1);
        log.info("[PageViewDailyAggregation] 日次集計バッチ開始: date={}", yesterday);
        try {
            aggregateForDate(yesterday);
            log.info("[PageViewDailyAggregation] 日次集計バッチ完了: date={}", yesterday);
        } catch (Exception e) {
            log.error("[PageViewDailyAggregation] 日次集計バッチ失敗: date={}", yesterday, e);
            throw e;
        }
    }

    /**
     * 指定日の日次集計を（再）実行する。バックフィル用の公開メソッド（AC-19）。
     *
     * <p>冪等: 対象日の集計結果 scope をすべて {@code delete → insert} する。
     * 既存行が残る可能性のある scope（当日に生ログが消えたケース等）を漏らさないため、
     * 集計に現れた scope のみを対象に delete→insert する（生ログ 0 件の scope は行を作らない・AC-20）。</p>
     *
     * @param date 集計対象日（JST 日付）
     */
    @Transactional
    public void aggregateForDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime toExclusive = date.plusDays(1).atStartOfDay();

        List<PageViewLogRepository.ScopeDailyAggregate> aggregates =
                logRepository.aggregateByScopeForPeriod(from, toExclusive);

        for (PageViewLogRepository.ScopeDailyAggregate agg : aggregates) {
            PageViewScopeType scopeType = PageViewScopeType.valueOf(agg.getScopeType());
            Long scopeId = agg.getScopeId();

            // 冪等: 対象 scope × 日付の既存行を削除してから再 INSERT
            dailyStatsRepository.deleteByScopeTypeAndScopeIdAndDate(scopeType, scopeId, date);

            PageViewDailyStatsEntity entity = PageViewDailyStatsEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .date(date)
                    .totalViews(toInt(agg.getTotalViews()))
                    .uniqueVisitors(toInt(agg.getUniqueVisitors()))
                    .memberViews(toInt(agg.getMemberViews()))
                    .guestViews(toInt(agg.getGuestViews()))
                    .build();
            dailyStatsRepository.save(entity);
        }
        log.debug("[PageViewDailyAggregation] date={} 集計 scope 数={}", date, aggregates.size());
    }

    /**
     * long（集計値）を int（集計テーブルの列型）へ安全に変換する。
     * PV は int の範囲を実運用で超えないが、万一の桁溢れは {@link Integer#MAX_VALUE} に丸める。
     */
    private int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < 0) {
            return 0;
        }
        return (int) value;
    }
}
