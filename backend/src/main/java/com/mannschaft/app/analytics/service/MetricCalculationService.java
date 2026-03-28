package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * KPI計算のコアロジックを提供するサービス。
 *
 * <p>MRR/ARR/ARPU/LTV/NRR/Quick Ratio 等の SaaS メトリクスを算出する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class MetricCalculationService {

    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;

    /**
     * 指定月のMRRを計算する。
     *
     * <p>定期課金収益源のみ（MODULE_SUBSCRIPTION + STORAGE_ADDON + ORG_COUNT_BILLING）を対象とする。</p>
     */
    public BigDecimal calculateMrr(LocalDate monthStart, LocalDate monthEnd) {
        List<AnalyticsDailyRevenueEntity> records = revenueRepository
                .findByDateBetweenOrderByDateAsc(monthStart, monthEnd);
        return records.stream()
                .filter(r -> r.getRevenueSource() == RevenueSource.MODULE_SUBSCRIPTION
                        || r.getRevenueSource() == RevenueSource.STORAGE_ADDON
                        || r.getRevenueSource() == RevenueSource.ORG_COUNT_BILLING)
                .map(AnalyticsDailyRevenueEntity::getNetRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * ARR = MRR x 12 を算出する。
     */
    public BigDecimal calculateArr(BigDecimal mrr) {
        return mrr.multiply(BigDecimal.valueOf(12));
    }

    /**
     * ARPU = 月間純収益 / アクティブユーザー数。
     *
     * <p>activeUsers が 0 の場合は null を返す。</p>
     */
    public BigDecimal calculateArpu(BigDecimal netRevenue, int activeUsers) {
        if (activeUsers == 0) {
            return null;
        }
        return netRevenue.divide(BigDecimal.valueOf(activeUsers), 2, RoundingMode.HALF_UP);
    }

    /**
     * LTV = ARPU / 月次チャーンレート。
     *
     * <p>ARPU またはチャーンレートが null もしくは 0 の場合は null を返す。</p>
     */
    public BigDecimal calculateLtv(BigDecimal arpu, BigDecimal monthlyChurnRate) {
        if (arpu == null || monthlyChurnRate == null
                || monthlyChurnRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal rateDecimal = monthlyChurnRate
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return arpu.divide(rateDecimal, 2, RoundingMode.HALF_UP);
    }

    /**
     * NRR = (月初MRR + Expansion - Churned) / 月初MRR x 100。
     *
     * <p>月初MRR が null もしくは 0 の場合は null を返す。</p>
     */
    public BigDecimal calculateNrr(BigDecimal beginningMrr,
                                   BigDecimal expansionMrr,
                                   BigDecimal churnedMrr) {
        if (beginningMrr == null || beginningMrr.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return beginningMrr.add(expansionMrr).subtract(churnedMrr)
                .divide(beginningMrr, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Quick Ratio = (新規 + 復活 + Expansion) / Churned。
     *
     * <p>Churned が null もしくは 0 の場合は null を返す。</p>
     */
    public BigDecimal calculateQuickRatio(BigDecimal newMrr,
                                          BigDecimal reactivationMrr,
                                          BigDecimal expansionMrr,
                                          BigDecimal churnedMrr) {
        if (churnedMrr == null || churnedMrr.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return newMrr.add(reactivationMrr).add(expansionMrr)
                .divide(churnedMrr, 4, RoundingMode.HALF_UP);
    }

    /**
     * Payback Period = マーケティングコスト / 新規顧客ARPU（月数）。
     *
     * <p>新規顧客ARPU が null もしくは 0 の場合は null を返す。</p>
     */
    public BigDecimal calculatePaybackMonths(BigDecimal marketingCost,
                                             BigDecimal newCustomerArpu) {
        if (newCustomerArpu == null || newCustomerArpu.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return marketingCost.divide(newCustomerArpu, 2, RoundingMode.HALF_UP);
    }

    /**
     * ユーザーチャーンレート = 解約数 / 月初課金ユーザー数 x 100。
     *
     * <p>月初課金ユーザー数が 0 の場合は 0 を返す。</p>
     */
    public BigDecimal calculateUserChurnRate(int churnedUsers, int beginningPayingUsers) {
        if (beginningPayingUsers == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(churnedUsers)
                .divide(BigDecimal.valueOf(beginningPayingUsers), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 収益チャーンレート = 解約MRR / 月初MRR x 100。
     *
     * <p>月初MRR が null もしくは 0 の場合は 0 を返す。</p>
     */
    public BigDecimal calculateRevenueChurnRate(BigDecimal churnedMrr,
                                                BigDecimal beginningMrr) {
        if (beginningMrr == null || beginningMrr.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return churnedMrr.divide(beginningMrr, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
