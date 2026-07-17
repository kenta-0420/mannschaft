package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * F17.1 ②-2 — 村ニュースレター集計・凍結バッチ（設計書 §4.2 / §5）。
 *
 * <p>毎日 03:00（UTC）に全ニュースレター設定を走査し、当日が各村の <b>集計日</b> に当たるものについて
 * その期間のダイジェストを集計して号を凍結する（{@link VillageNewsletterIssueService#aggregateAndFreeze}）。
 * ラグを経て配信日に配信するのは ②-3 の配信バッチ（本バッチは触らない）。</p>
 *
 * <h2>集計日の判定（設計書 §4.3）</h2>
 * <ul>
 *   <li>WEEKLY: {@code aggregate_day} は曜日（1=月 … 7=日）。当日の曜日と一致した村を集計する。</li>
 *   <li>MONTHLY: {@code aggregate_day} は日付（1〜28、{@code 0}=月末の番兵値）。当日がその日なら集計する。</li>
 * </ul>
 *
 * <h2>原則準拠・耐障害</h2>
 * <ul>
 *   <li>原則5: 1 村 = 1 トランザクション（{@link VillageNewsletterIssueService} 側）。1 件失敗しても次へ進む
 *       （村史バッチ・配信バッチと同じ error-continue）。</li>
 *   <li>ShedLock で複数インスタンス起動時の二重実行を防ぐ。集計 Service 側の冪等判定と二段で守る。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterAggregateBatchService {

    private final VillageNewsletterRepository newsletterRepository;
    private final VillageNewsletterIssueRepository issueRepository;
    private final VillageNewsletterIssueService issueService;

    /**
     * 毎日 03:00 UTC に、当日が集計日に当たる村のダイジェストを集計・凍結する。
     */
    @BatchEndpoint(name = "village-newsletter-aggregate-daily",
            description = "当日が集計日の村ニュースレターを集計・凍結する（毎日 03:00 UTC）")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageNewsletterAggregateBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runDailyAggregate() {
        aggregateForDate(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * 指定日を「今日」として集計・凍結を実行する（テスト・再実行用に日付を注入可能にした委譲先）。
     *
     * @param today 集計基準日（UTC のカレンダー日）
     * @return 号を集計・凍結した村数
     */
    public int aggregateForDate(LocalDate today) {
        log.info("ニュースレター集計・凍結バッチ開始: today={}", today);
        int aggregated = 0;
        int failed = 0;

        for (VillageNewsletterFrequency frequency : VillageNewsletterFrequency.values()) {
            for (VillageNewsletterEntity nl :
                    newsletterRepository.findByFrequencyAndIsEnabledTrueAndDeletedAtIsNull(frequency)) {
                try {
                    if (aggregateOne(today, nl)) {
                        aggregated++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("ニュースレター集計失敗: newsletterId={} villageId={} today={}",
                            nl.getId(), nl.getVillageId(), today, e);
                }
            }
        }

        log.info("ニュースレター集計・凍結バッチ完了: 集計={} 失敗={} today={}", aggregated, failed, today);
        return aggregated;
    }

    /**
     * 1 設定について、当日が集計日なら集計・凍結する。
     *
     * @return 集計を実行した場合 true（集計日でなければ false）
     */
    private boolean aggregateOne(LocalDate today, VillageNewsletterEntity nl) {
        if (!isAggregateDay(today, nl)) {
            return false;
        }
        LocalDateTime periodEnd = today.atStartOfDay();
        LocalDateTime periodStart = resolvePeriodStart(nl, periodEnd);
        LocalDateTime scheduledPublishAt = resolveScheduledPublishAt(today, nl);

        issueService.aggregateAndFreeze(
                nl.getVillageId(), nl.getFrequency(), nl.getId(),
                periodStart, periodEnd, scheduledPublishAt);
        return true;
    }

    /** 当日が集計日か（設計書 §4.3）。 */
    private boolean isAggregateDay(LocalDate today, VillageNewsletterEntity nl) {
        int aggregateDay = nl.getAggregateDay();
        if (nl.getFrequency() == VillageNewsletterFrequency.WEEKLY) {
            return today.getDayOfWeek().getValue() == aggregateDay; // 月=1 … 日=7
        }
        // MONTHLY: 0=月末の番兵値、それ以外は日付一致
        if (aggregateDay == 0) {
            return today.getDayOfMonth() == today.lengthOfMonth();
        }
        return today.getDayOfMonth() == aggregateDay;
    }

    /**
     * 集計期間の開始を決める（設計書 §5.2）。
     *
     * <p>直近同村×頻度号の {@code period_end}（今回の集計基準時刻より前に終わった号）から始め、
     * 期間を連続させる。無ければ頻度に応じて 1 期間遡る（WEEKLY=1週・MONTHLY=1ヶ月）。</p>
     */
    private LocalDateTime resolvePeriodStart(VillageNewsletterEntity nl, LocalDateTime periodEnd) {
        return issueRepository
                .findFirstByVillageIdAndFrequencyAndPeriodEndLessThanAndDeletedAtIsNullOrderByPeriodEndDesc(
                        nl.getVillageId(), nl.getFrequency(), periodEnd)
                .map(VillageNewsletterIssueEntity::getPeriodEnd)
                .orElseGet(() -> nl.getFrequency() == VillageNewsletterFrequency.WEEKLY
                        ? periodEnd.minusWeeks(1)
                        : periodEnd.minusMonths(1));
    }

    /**
     * 配信予定時刻を決める（設計書 §4.3）。
     * today 以降で最初に配信日（{@code dispatch_day}）が来る日の {@code dispatch_hour} 時。
     */
    private LocalDateTime resolveScheduledPublishAt(LocalDate today, VillageNewsletterEntity nl) {
        LocalDate dispatchDate = nl.getFrequency() == VillageNewsletterFrequency.WEEKLY
                ? nextWeeklyDispatchDate(today, nl.getDispatchDay())
                : nextMonthlyDispatchDate(today, nl.getDispatchDay());
        return dispatchDate.atTime(nl.getDispatchHour(), 0);
    }

    /** today 以降で最初に指定曜日（1=月 … 7=日）が来る日。 */
    private LocalDate nextWeeklyDispatchDate(LocalDate today, int dispatchDayOfWeek) {
        int todayDow = today.getDayOfWeek().getValue();
        int delta = ((dispatchDayOfWeek - todayDow) % 7 + 7) % 7; // 0=当日
        return today.plusDays(delta);
    }

    /** today 以降で最初に指定日（1〜28、0=月末）が来る日。今月分が過ぎていれば翌月。 */
    private LocalDate nextMonthlyDispatchDate(LocalDate today, int dispatchDayOfMonth) {
        LocalDate thisMonth = monthlyDispatchDateFor(today.getYear(), today.getMonthValue(), dispatchDayOfMonth);
        if (!thisMonth.isBefore(today)) {
            return thisMonth;
        }
        LocalDate nextMonthFirst = today.withDayOfMonth(1).plusMonths(1);
        return monthlyDispatchDateFor(nextMonthFirst.getYear(), nextMonthFirst.getMonthValue(), dispatchDayOfMonth);
    }

    /** 指定年月における配信日（0=月末、それ以外は日付。月の日数を超える指定は月末に丸める）。 */
    private LocalDate monthlyDispatchDateFor(int year, int month, int dispatchDayOfMonth) {
        LocalDate first = LocalDate.of(year, month, 1);
        if (dispatchDayOfMonth == 0) {
            return first.withDayOfMonth(first.lengthOfMonth());
        }
        return first.withDayOfMonth(Math.min(dispatchDayOfMonth, first.lengthOfMonth()));
    }
}
