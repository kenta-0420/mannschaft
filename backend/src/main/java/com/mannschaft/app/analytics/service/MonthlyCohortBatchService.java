package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 月次コホート分析バッチ。毎月1日 AM 3:00 (JST) に過去24ヶ月分のコホートを再計算する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyCohortBatchService {

    private final AnalyticsMonthlyCohortRepository cohortRepository;
    private static final int COHORT_LOOKBACK_MONTHS = 24;

    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "monthlyCohortAggregation", lockAtMostFor = "60m", lockAtLeastFor = "10m")
    @Transactional
    public void execute() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        LocalDate targetMonth = now.minusMonths(1).withDayOfMonth(1);
        log.info("月次コホートバッチ開始: targetMonth={}", targetMonth);

        try {
            recalculateCohorts(targetMonth);
            log.info("月次コホートバッチ完了");
        } catch (Exception e) {
            log.error("月次コホートバッチ失敗", e);
            throw e;
        }
    }

    /**
     * 指定基準月までの過去24ヶ月分のコホートを再計算する（バックフィル用）。
     */
    @Transactional
    public void recalculateForMonth(LocalDate targetMonth) {
        recalculateCohorts(targetMonth);
    }

    private void recalculateCohorts(LocalDate upToMonth) {
        for (int i = COHORT_LOOKBACK_MONTHS; i >= 0; i--) {
            LocalDate cohortMonth = upToMonth.minusMonths(i).withDayOfMonth(1);
            long monthsElapsed = ChronoUnit.MONTHS.between(cohortMonth, upToMonth);

            for (int m = 0; m <= monthsElapsed; m++) {
                // TODO: users + member_subscriptions から実データで計算
                // cohort_size: cohortMonth に登録したユーザー数
                // retained_users: m ヶ月後にアクティブなユーザー数
                // retained_paying: m ヶ月後に課金中のユーザー数
                // revenue: m ヶ月目のコホート収益
                AnalyticsMonthlyCohortEntity entity = AnalyticsMonthlyCohortEntity.builder()
                        .cohortMonth(cohortMonth)
                        .monthsElapsed(m)
                        .build();
                cohortRepository.save(entity);
            }
        }
    }
}
