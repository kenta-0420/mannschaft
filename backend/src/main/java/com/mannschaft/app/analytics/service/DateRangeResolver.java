package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.Granularity;
import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * 日付範囲プリセットの解決とバリデーション。
 */
@Component
public class DateRangeResolver {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final int MAX_DAILY_DAYS = 90;
    private static final int MAX_WEEKLY_DAYS = 364;
    private static final int MAX_MONTHLY_MONTHS = 36;
    private static final int MAX_EXPORT_DAYS = 365;

    @Getter
    @RequiredArgsConstructor
    public static class DateRange {
        private final LocalDate from;
        private final LocalDate to;
    }

    /**
     * preset が指定されていればそちらを優先して日付範囲を解決する。
     */
    public DateRange resolve(LocalDate from, LocalDate to, DatePreset preset) {
        if (preset != null) {
            return resolvePreset(preset);
        }
        if (from == null || to == null) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_005);
        }
        if (from.isAfter(to)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_005);
        }
        return new DateRange(from, to);
    }

    /**
     * 粒度ごとの日付範囲上限を検証する。
     */
    public void validateGranularity(DateRange range, Granularity granularity) {
        long days = ChronoUnit.DAYS.between(range.getFrom(), range.getTo()) + 1;
        switch (granularity) {
            case DAILY -> {
                if (days > MAX_DAILY_DAYS) {
                    throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
                }
            }
            case WEEKLY -> {
                if (days > MAX_WEEKLY_DAYS) {
                    throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
                }
            }
            case MONTHLY -> {
                long months = ChronoUnit.MONTHS.between(range.getFrom(), range.getTo()) + 1;
                if (months > MAX_MONTHLY_MONTHS) {
                    throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
                }
            }
        }
    }

    /**
     * エクスポート用の日付範囲上限を検証する（最大365日）。
     */
    public void validateExportRange(DateRange range) {
        long days = ChronoUnit.DAYS.between(range.getFrom(), range.getTo()) + 1;
        if (days > MAX_EXPORT_DAYS) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_007);
        }
    }

    private DateRange resolvePreset(DatePreset preset) {
        LocalDate today = LocalDate.now(JST);
        return switch (preset) {
            case LAST_7D -> new DateRange(today.minusDays(6), today);
            case LAST_30D -> new DateRange(today.minusDays(29), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today);
            case LAST_MONTH -> {
                LocalDate firstOfLastMonth = today.minusMonths(1).withDayOfMonth(1);
                yield new DateRange(firstOfLastMonth,
                        firstOfLastMonth.with(TemporalAdjusters.lastDayOfMonth()));
            }
            case THIS_QUARTER -> {
                int quarterStart = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                yield new DateRange(LocalDate.of(today.getYear(), quarterStart, 1), today);
            }
            case LAST_QUARTER -> {
                int currentQuarterStart = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate lastQuarterEnd = LocalDate.of(today.getYear(), currentQuarterStart, 1)
                        .minusDays(1);
                int lastQuarterStartMonth =
                        ((lastQuarterEnd.getMonthValue() - 1) / 3) * 3 + 1;
                yield new DateRange(
                        LocalDate.of(lastQuarterEnd.getYear(), lastQuarterStartMonth, 1),
                        lastQuarterEnd);
            }
            case YTD -> new DateRange(LocalDate.of(today.getYear(), 1, 1), today);
            case LAST_12M -> new DateRange(today.minusMonths(12).plusDays(1), today);
        };
    }
}
