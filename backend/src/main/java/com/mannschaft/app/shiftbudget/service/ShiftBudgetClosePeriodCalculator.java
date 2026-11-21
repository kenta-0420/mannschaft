package com.mannschaft.app.shiftbudget.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Function;

/**
 * 組織の締め日から締め期間を算出する純粋関数（CMP-260909-2152 確定設計 r12 §2）。
 *
 * <ul>
 *   <li>closingDate(M, d) = d=0 ? M の末日 : M.atDay(d)</li>
 *   <li>Period(M) = [closingDate(M-1, d(M-1)) + 1, closingDate(M, d(M))]（連続・非重複）</li>
 *   <li>期間キー＝締め日が属する月</li>
 *   <li>仕訳日 ＝ min(締め日, 割当終了日)</li>
 * </ul>
 *
 * <p>{@code closingDayOf} は期間キー M に有効な締め日（0〜28、0＝末日）を返す関数
 * （effective_period_key &lt;= M の変更のうち revision 最大、無ければ0）。</p>
 *
 */
public final class ShiftBudgetClosePeriodCalculator {

    private ShiftBudgetClosePeriodCalculator() {
    }

    /** 期間キー M・締め日 d の締め日（d=0 は末日）。 */
    public static LocalDate closingDate(YearMonth periodKey, int closingDay) {
        return closingDay == 0 ? periodKey.atEndOfMonth() : periodKey.atDay(closingDay);
    }

    /** 期間キー M の締め期間。 */
    public static ClosePeriod period(YearMonth periodKey, Function<YearMonth, Integer> closingDayOf) {
        int day = closingDayOf.apply(periodKey);
        YearMonth previous = periodKey.minusMonths(1);
        LocalDate start = closingDate(previous, closingDayOf.apply(previous)).plusDays(1);
        return new ClosePeriod(periodKey, start, closingDate(periodKey, day), day);
    }

    /** 勤務日（深夜跨ぎは開始日）が属する期間キー。 */
    public static YearMonth periodKeyOf(LocalDate workDate, Function<YearMonth, Integer> closingDayOf) {
        YearMonth month = YearMonth.from(workDate);
        LocalDate end = closingDate(month, closingDayOf.apply(month));
        return workDate.isAfter(end) ? month.plusMonths(1) : month;
    }

    /** 仕訳日 ＝ min(締め日, 割当終了日)。 */
    public static LocalDate journalDate(ClosePeriod period, LocalDate allocationEnd) {
        return period.end().isAfter(allocationEnd) ? allocationEnd : period.end();
    }

    /**
     * 締め期間。
     *
     * @param periodKey  期間キー（締め日が属する月）
     * @param start      期間開始日（含む）
     * @param end        期間終了日＝締め日（含む）
     * @param closingDay この期間の締め日（0＝末日）
     */
    public record ClosePeriod(YearMonth periodKey, LocalDate start, LocalDate end, int closingDay) {
    }
}
