package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.dto.ForecastResponse;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlySnapshotRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 収益予測を実行するサービス。
 *
 * <p>直近のスナップショットデータから線形回帰または成長率ベースの予測を行い、
 * 信頼区間付きの予測値を返す。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AnalyticsForecastService {

    private static final Set<Integer> ALLOWED_MONTHS = Set.of(3, 6, 12);
    private static final int MIN_DATA_POINTS_REGRESSION = 6;
    private static final int MIN_DATA_POINTS_GROWTH = 3;
    private static final double CONFIDENCE_Z = 1.96;
    private static final double GROWTH_CONFIDENCE_PCT = 0.15;

    private final AnalyticsMonthlySnapshotRepository snapshotRepository;

    /**
     * 収益予測を実行する。
     *
     * @param months 予測月数（3, 6, 12 のいずれか）
     * @return 予測レスポンス
     */
    public ForecastResponse forecast(int months) {
        if (!ALLOWED_MONTHS.contains(months)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_008);
        }

        // 直近6ヶ月のスナップショットを取得
        YearMonth now = YearMonth.now();
        LocalDate toDate = now.atEndOfMonth();
        LocalDate fromDate = now.minusMonths(5).atDay(1);

        List<AnalyticsMonthlySnapshotEntity> snapshots = snapshotRepository
                .findByMonthBetweenOrderByMonthAsc(fromDate, toDate);

        int dataPoints = snapshots.size();
        log.debug("予測実行: months={}, dataPoints={}", months, dataPoints);

        if (dataPoints < MIN_DATA_POINTS_GROWTH) {
            // データ不足: 空の予測を返す
            // ForecastResponse fields: baseDate, currentMrr, currentUserCount, forecast, assumptions
            var assumptions = new ForecastResponse.ForecastAssumptions(
                    BigDecimal.ZERO, BigDecimal.ZERO, "INSUFFICIENT_DATA"
            );
            return new ForecastResponse(
                    LocalDate.now(), BigDecimal.ZERO, 0, List.of(), assumptions
            );
        }

        List<BigDecimal> mrrValues = snapshots.stream()
                .map(AnalyticsMonthlySnapshotEntity::getMrr)
                .toList();

        if (dataPoints >= MIN_DATA_POINTS_REGRESSION) {
            return forecastByRegression(months, mrrValues, snapshots);
        } else {
            return forecastByGrowthRate(months, mrrValues, snapshots);
        }
    }

    /**
     * 線形回帰による予測。データ点 >= 6 の場合に使用。
     */
    private ForecastResponse forecastByRegression(int months, List<BigDecimal> mrrValues,
                                                  List<AnalyticsMonthlySnapshotEntity> snapshots) {
        double[] coefficients = linearRegression(mrrValues);
        double slope = coefficients[0];
        double intercept = coefficients[1];
        double stdDev = residualStdDev(mrrValues, slope, intercept);
        double confidence = CONFIDENCE_Z * stdDev;

        int baseIndex = mrrValues.size();
        AnalyticsMonthlySnapshotEntity lastSnapshot = snapshots.get(snapshots.size() - 1);
        YearMonth lastMonth = YearMonth.from(lastSnapshot.getMonth());

        // ForecastPoint fields: month, projectedMrr, projectedUsers, confidenceLow, confidenceHigh
        List<ForecastResponse.ForecastPoint> points = new ArrayList<>();
        for (int i = 1; i <= months; i++) {
            double predicted = intercept + slope * (baseIndex + i - 1);
            BigDecimal value = BigDecimal.valueOf(predicted)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal lower = BigDecimal.valueOf(predicted - confidence)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal upper = BigDecimal.valueOf(predicted + confidence)
                    .setScale(2, RoundingMode.HALF_UP);

            points.add(new ForecastResponse.ForecastPoint(
                    lastMonth.plusMonths(i).toString(),
                    value, 0, lower.max(BigDecimal.ZERO), upper
            ));
        }

        log.debug("線形回帰予測完了: slope={}, intercept={}, stdDev={}",
                slope, intercept, stdDev);

        BigDecimal currentMrr = mrrValues.get(mrrValues.size() - 1);
        var assumptions = new ForecastResponse.ForecastAssumptions(
                null, null, "LINEAR_REGRESSION"
        );
        return new ForecastResponse(
                lastSnapshot.getMonth(), currentMrr, lastSnapshot.getActiveUsers(),
                points, assumptions
        );
    }

    /**
     * 成長率ベースの単純予測。データ点 3-5 の場合に使用。
     */
    private ForecastResponse forecastByGrowthRate(int months, List<BigDecimal> mrrValues,
                                                  List<AnalyticsMonthlySnapshotEntity> snapshots) {
        // 直近3ヶ月の平均成長率
        BigDecimal avgGrowthRate = calculateAverageGrowthRate(mrrValues);
        BigDecimal current = mrrValues.get(mrrValues.size() - 1);
        AnalyticsMonthlySnapshotEntity lastSnapshot = snapshots.get(snapshots.size() - 1);
        YearMonth lastMonth = YearMonth.from(lastSnapshot.getMonth());

        List<ForecastResponse.ForecastPoint> points = new ArrayList<>();
        BigDecimal projected = current;

        for (int i = 1; i <= months; i++) {
            projected = projected.multiply(BigDecimal.ONE.add(avgGrowthRate))
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal margin = projected.multiply(BigDecimal.valueOf(GROWTH_CONFIDENCE_PCT))
                    .setScale(2, RoundingMode.HALF_UP);

            points.add(new ForecastResponse.ForecastPoint(
                    lastMonth.plusMonths(i).toString(),
                    projected, 0,
                    projected.subtract(margin).max(BigDecimal.ZERO),
                    projected.add(margin)
            ));
        }

        log.debug("成長率ベース予測完了: avgGrowthRate={}", avgGrowthRate);

        var assumptions = new ForecastResponse.ForecastAssumptions(
                avgGrowthRate, null, "GROWTH_RATE"
        );
        return new ForecastResponse(
                lastSnapshot.getMonth(), current, lastSnapshot.getActiveUsers(),
                points, assumptions
        );
    }

    /**
     * 直近3ヶ月の平均月次成長率を算出する。
     */
    private BigDecimal calculateAverageGrowthRate(List<BigDecimal> values) {
        int size = values.size();
        int lookback = Math.min(3, size - 1);
        BigDecimal totalRate = BigDecimal.ZERO;

        for (int i = size - lookback; i < size; i++) {
            BigDecimal prev = values.get(i - 1);
            BigDecimal curr = values.get(i);
            if (prev.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal rate = curr.subtract(prev)
                        .divide(prev, 4, RoundingMode.HALF_UP);
                totalRate = totalRate.add(rate);
            }
        }

        return lookback == 0 ? BigDecimal.ZERO
                : totalRate.divide(BigDecimal.valueOf(lookback), 4, RoundingMode.HALF_UP);
    }

    /**
     * 最小二乗法で線形回帰の係数（slope, intercept）を算出する。
     */
    private double[] linearRegression(List<BigDecimal> values) {
        int n = values.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = values.get(i).doubleValue();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = n * sumX2 - sumX * sumX;
        double slope = (n * sumXY - sumX * sumY) / denominator;
        double interceptVal = (sumY - slope * sumX) / n;

        return new double[]{slope, interceptVal};
    }

    /**
     * 残差の標準偏差を算出する。
     */
    private double residualStdDev(List<BigDecimal> values, double slope, double intercept) {
        int n = values.size();
        if (n <= 2) {
            return 0;
        }

        double sumSqResidual = 0;
        for (int i = 0; i < n; i++) {
            double predicted = intercept + slope * i;
            double residual = values.get(i).doubleValue() - predicted;
            sumSqResidual += residual * residual;
        }

        return Math.sqrt(sumSqResidual / (n - 2));
    }
}
