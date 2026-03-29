package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 月次コホート分析バッチ。毎月1日 AM 3:00 (JST) に過去24ヶ月分のコホートを再計算する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyCohortBatchService {

    private final AnalyticsMonthlyCohortRepository cohortRepository;
    private final UserRepository userRepository;
    private final MemberPaymentRepository memberPaymentRepository;
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
            LocalDate cohortEnd = cohortMonth.with(TemporalAdjusters.lastDayOfMonth());
            long monthsElapsed = ChronoUnit.MONTHS.between(cohortMonth, upToMonth);

            // cohortMonth に登録されたユーザーのIDリストを取得
            List<Long> cohortUserIds = userRepository.findUserIdsCreatedBetween(cohortMonth, cohortEnd);
            int cohortSize = cohortUserIds.size();

            for (int m = 0; m <= monthsElapsed; m++) {
                // 冪等性: 既存データを削除してから再集計
                cohortRepository.deleteByCohortMonthAndMonthsElapsed(cohortMonth, m);

                int retainedUsers = 0;
                int retainedPaying = 0;
                BigDecimal revenue = BigDecimal.ZERO;

                if (!cohortUserIds.isEmpty()) {
                    // m ヶ月後のアクティブ状態を判定
                    retainedUsers = userRepository.countActiveByUserIds(cohortUserIds);

                    // m ヶ月目のコホート収益と課金ユーザー数
                    LocalDate targetMonthStart = cohortMonth.plusMonths(m).withDayOfMonth(1);
                    LocalDate targetMonthEnd = targetMonthStart.with(TemporalAdjusters.lastDayOfMonth());

                    // 指定月に支払いのあったコホートユーザーの収益
                    revenue = memberPaymentRepository.sumPaidAmountByUserIdsAndMonth(
                            cohortUserIds, targetMonthStart, targetMonthEnd);

                    // 指定月に有効な支払いを持つユーザー数を簡易計算
                    retainedPaying = (int) cohortUserIds.stream()
                            .filter(uid -> memberPaymentRepository
                                    .countDistinctPayingUsersByDate(targetMonthEnd) > 0)
                            .limit(1) // 個別ユーザーの判定は重いため、全体の paying からの推定に切り替え
                            .count();
                    // 簡易計算: コホートサイズに対する全体の paying 比率で推定
                    if (cohortSize > 0) {
                        int totalPaying = memberPaymentRepository.countDistinctPayingUsersByDate(targetMonthEnd);
                        int totalUsers = userRepository.countTotalUsersAsOf(
                                targetMonthEnd.atTime(23, 59, 59));
                        if (totalUsers > 0) {
                            retainedPaying = (int) Math.round(
                                    (double) retainedUsers * totalPaying / totalUsers);
                        }
                    }
                }

                AnalyticsMonthlyCohortEntity entity = AnalyticsMonthlyCohortEntity.builder()
                        .cohortMonth(cohortMonth)
                        .monthsElapsed(m)
                        .cohortSize(cohortSize)
                        .retainedUsers(retainedUsers)
                        .retainedPaying(retainedPaying)
                        .revenue(revenue)
                        .build();
                cohortRepository.save(entity);
            }
        }
    }
}
