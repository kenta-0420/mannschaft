package com.mannschaft.app.shiftbudget.closeperiod;

import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodCalculator;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodCalculator.ClosePeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 PR-α 試練104（AC26）・試練109（AC32）・試練110（AC33）: 締め期間算出の純粋関数。
 *
 * <p>期間算出は DB に依存しないため Unit のプロパティテストで網羅する。
 * 実装前は {@link ShiftBudgetClosePeriodCalculator} の骨組みが UnsupportedOperationException を投げて red。</p>
 */
@DisplayName("CMP-260909-2152 α 締め期間の算出（Unit・プロパティ）")
class ShiftBudgetClosePeriodCalculatorTest {

    /** 締め日の変更履歴（effective_period_key → closing_day）から、期間キー M の締め日を返す関数を作る。 */
    private static Function<YearMonth, Integer> history(Map<YearMonth, Integer> changes) {
        TreeMap<YearMonth, Integer> sorted = new TreeMap<>(changes);
        return m -> {
            Map.Entry<YearMonth, Integer> e = sorted.floorEntry(m);
            return e == null ? 0 : e.getValue();
        };
    }

    static Stream<Arguments> changePatterns() {
        return Stream.of(
                Arguments.of("末日のみ", Map.of()),
                Arguments.of("末日→15日（2026-05 適用）", Map.of(YearMonth.of(2026, 5), 15)),
                Arguments.of("15日→末日（2026-06 適用）", Map.of(YearMonth.of(2025, 1), 15, YearMonth.of(2026, 6), 0)),
                Arguments.of("1日→28日→10日（移行期間2回）",
                        Map.of(YearMonth.of(2025, 1), 1, YearMonth.of(2025, 9), 28, YearMonth.of(2026, 3), 10)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changePatterns")
    @DisplayName("試練104 AC26 締め日変更4パターンで2年分の全日がちょうど1期間に属し、期間は連続・非重複")
    void 試練104_全日がちょうど1期間に属する(String name, Map<YearMonth, Integer> changes) {
        Function<YearMonth, Integer> d = history(changes);
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);

        // 期間キーを連続に並べ、前の期間の終了日の翌日が次の期間の開始日であること
        ClosePeriod prev = null;
        for (YearMonth m = YearMonth.of(2024, 12); !m.isAfter(YearMonth.of(2027, 1)); m = m.plusMonths(1)) {
            ClosePeriod p = ShiftBudgetClosePeriodCalculator.period(m, d);
            assertThat(p.periodKey()).isEqualTo(m);
            assertThat(p.start()).as("%s の開始 <= 終了", m).isBeforeOrEqualTo(p.end());
            assertThat(YearMonth.from(p.end())).as("期間キー＝締め日が属する月").isEqualTo(m);
            assertThat(p.closingDay()).isEqualTo(d.apply(m));
            if (prev != null) {
                assertThat(p.start()).as("%s は前期間の翌日から始まる（隙間・重なりなし）", m)
                        .isEqualTo(prev.end().plusDays(1));
            }
            prev = p;
        }

        // 全日が periodKeyOf の期間に含まれ、その期間は1つだけ
        Map<YearMonth, ClosePeriod> cache = new HashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            YearMonth key = ShiftBudgetClosePeriodCalculator.periodKeyOf(day, d);
            ClosePeriod p = cache.computeIfAbsent(key, k -> ShiftBudgetClosePeriodCalculator.period(k, d));
            assertThat(day).as("%s は期間 %s に含まれる", day, key).isBetween(p.start(), p.end());
            ClosePeriod before = ShiftBudgetClosePeriodCalculator.period(key.minusMonths(1), d);
            ClosePeriod after = ShiftBudgetClosePeriodCalculator.period(key.plusMonths(1), d);
            assertThat(day.isAfter(before.end()) && day.isBefore(after.start()))
                    .as("%s は隣の期間に属さない", day).isTrue();
        }
    }

    @Test
    @DisplayName("試練104 AC26 ランダムな締め日履歴（seed 固定 200通り）でも全日がちょうど1期間に属する")
    void 試練104_ランダム履歴() {
        Random random = new Random(2152L);
        for (int trial = 0; trial < 200; trial++) {
            Map<YearMonth, Integer> changes = new HashMap<>();
            int n = random.nextInt(4);
            for (int i = 0; i < n; i++) {
                changes.put(YearMonth.of(2025, 1).plusMonths(random.nextInt(24)), random.nextInt(29));
            }
            Function<YearMonth, Integer> d = history(changes);
            ClosePeriod prev = ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2024, 12), d);
            for (YearMonth m = YearMonth.of(2025, 1); !m.isAfter(YearMonth.of(2026, 12)); m = m.plusMonths(1)) {
                ClosePeriod p = ShiftBudgetClosePeriodCalculator.period(m, d);
                assertThat(p.start()).as("trial=%d changes=%s key=%s", trial, changes, m)
                        .isEqualTo(prev.end().plusDays(1));
                assertThat(p.start()).isBeforeOrEqualTo(p.end());
                prev = p;
            }
        }
    }

    @Test
    @DisplayName("試練104 AC26 設計例: 15日締め 2026-05＝4/16〜5/15、2027-01＝12/16〜1/15、末日→15日（2026-05）の 2026-05＝5/1〜5/15、15日→末日（2026-06）の 2026-06＝5/16〜6/30")
    void 試練104_設計例() {
        Function<YearMonth, Integer> fifteen = m -> 15;
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2026, 5), fifteen), "2026-04-16", "2026-05-15");
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2027, 1), fifteen), "2026-12-16", "2027-01-15");
        Function<YearMonth, Integer> endTo15 = history(Map.of(YearMonth.of(2026, 5), 15));
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2026, 5), endTo15), "2026-05-01", "2026-05-15");
        Function<YearMonth, Integer> fifteenToEnd = history(Map.of(YearMonth.of(2020, 1), 15, YearMonth.of(2026, 6), 0));
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2026, 6), fifteenToEnd), "2026-05-16", "2026-06-30");
    }

    @Test
    @DisplayName("試練109 AC32 28日締めの2月・閏年（2028-03＝2/29〜3/28、2028-02＝1/29〜2/28）、末日締め 2028-02＝2/1〜2/29")
    void 試練109_28日締めと閏年() {
        Function<YearMonth, Integer> d28 = m -> 28;
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2028, 3), d28), "2028-02-29", "2028-03-28");
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2028, 2), d28), "2028-01-29", "2028-02-28");
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2027, 3), d28), "2027-03-01", "2027-03-28");
        Function<YearMonth, Integer> end = m -> 0;
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2028, 2), end), "2028-02-01", "2028-02-29");
        assertThat(ShiftBudgetClosePeriodCalculator.closingDate(YearMonth.of(2027, 2), 0)).isEqualTo("2027-02-28");
        assertThat(ShiftBudgetClosePeriodCalculator.periodKeyOf(LocalDate.of(2028, 2, 29), d28))
                .isEqualTo(YearMonth.of(2028, 3));
    }

    @Test
    @DisplayName("試練110 AC33 1日締め・年跨ぎ（2027-01＝12/2〜1/1、12/31 と 1/1 は 2027-01、1/2 は 2027-02）")
    void 試練110_1日締めと年跨ぎ() {
        Function<YearMonth, Integer> d1 = m -> 1;
        assertPeriod(ShiftBudgetClosePeriodCalculator.period(YearMonth.of(2027, 1), d1), "2026-12-02", "2027-01-01");
        assertThat(ShiftBudgetClosePeriodCalculator.periodKeyOf(LocalDate.of(2026, 12, 31), d1)).isEqualTo(YearMonth.of(2027, 1));
        assertThat(ShiftBudgetClosePeriodCalculator.periodKeyOf(LocalDate.of(2027, 1, 1), d1)).isEqualTo(YearMonth.of(2027, 1));
        assertThat(ShiftBudgetClosePeriodCalculator.periodKeyOf(LocalDate.of(2027, 1, 2), d1)).isEqualTo(YearMonth.of(2027, 2));
        // 15日締めの年跨ぎ: 12/16〜1/15 は翌年キー
        assertThat(ShiftBudgetClosePeriodCalculator.periodKeyOf(LocalDate.of(2026, 12, 20), m -> 15))
                .isEqualTo(YearMonth.of(2027, 1));
    }

    @Test
    @DisplayName("試練102 AC24（Unit 部）仕訳日＝min(締め日, 割当終了日)")
    void 試練102_仕訳日() {
        ClosePeriod p = new ClosePeriod(YearMonth.of(2026, 5), LocalDate.of(2026, 4, 16), LocalDate.of(2026, 5, 15), 15);
        assertThat(ShiftBudgetClosePeriodCalculator.journalDate(p, LocalDate.of(2026, 5, 10))).isEqualTo("2026-05-10");
        assertThat(ShiftBudgetClosePeriodCalculator.journalDate(p, LocalDate.of(2026, 6, 30))).isEqualTo("2026-05-15");
        assertThat(ShiftBudgetClosePeriodCalculator.journalDate(p, LocalDate.of(2026, 5, 15))).isEqualTo("2026-05-15");
    }

    private static void assertPeriod(ClosePeriod p, String start, String end) {
        assertThat(p.start()).as("開始").isEqualTo(start);
        assertThat(p.end()).as("終了").isEqualTo(end);
    }
}
