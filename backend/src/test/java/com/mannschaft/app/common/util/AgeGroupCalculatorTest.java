package com.mannschaft.app.common.util;

import com.mannschaft.app.auth.AgeGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgeGroupCalculator} の単体テスト。
 * 日本の学年制度（4月2日 cutoff）に基づくグループ計算と未成年判定を検証する。
 */
@DisplayName("AgeGroupCalculator 単体テスト")
class AgeGroupCalculatorTest {

    // ========================================
    // calculate（AgeGroup計算）
    // ========================================

    @Nested
    @DisplayName("calculate - AgeGroup 計算")
    class Calculate {

        @Test
        @DisplayName("N-01: 4月1日生まれは当年度扱い（小1境界: 2019-04-01, base: 2026-04-15 → ELEMENTARY_LOWER）")
        void n01_april1_birthday_elementary_lower() {
            LocalDate birthDate = LocalDate.of(2019, 4, 1);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.ELEMENTARY_LOWER);
        }

        @Test
        @DisplayName("N-02: 4月2日生まれは翌年度扱い（2019-04-02, base: 2026-04-15 → ELEMENTARY_LOWER）")
        void n02_april2_birthday_elementary_lower() {
            // 2019-04-02 生まれ: 学年基準日(2026-04-01)時点では6歳 → ELEMENTARY_LOWER
            LocalDate birthDate = LocalDate.of(2019, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.ELEMENTARY_LOWER);
        }

        @Test
        @DisplayName("N-03: 3月31日生まれは前の年度扱い（2018-03-31, base: 2026-04-15 → ELEMENTARY_MIDDLE）")
        void n03_march31_birthday_elementary_middle() {
            // 2018-03-31 生まれ: 学年基準日(2026-04-01)時点では8歳 → ELEMENTARY_MIDDLE
            LocalDate birthDate = LocalDate.of(2018, 3, 31);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.ELEMENTARY_MIDDLE);
        }

        @Test
        @DisplayName("N-04: 成人判定（1998-04-02, base: 2026-04-15 → ADULT）")
        void n04_adult() {
            // 1998-04-02 生まれ: 学年基準日(2026-04-01)時点では27歳 → ADULT
            LocalDate birthDate = LocalDate.of(1998, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.ADULT);
        }

        @Test
        @DisplayName("N-07: 1月1日生まれの年度内計算（2010-01-01, base: 2026-06-01 → SENIOR_HIGH）")
        void n07_january_birthday_senior_high() {
            // base: 2026-06-01 → monthValue(6) >= 4 → fiscalStart = 2026-04-01
            // ChronoUnit.YEARS.between(2010-01-01, 2026-04-01) = 16年 → SENIOR_HIGH
            // ※ 1月1日生まれは4月2日以降のため、同学年の4月生まれより1つ上の学年扱いに注意
            LocalDate birthDate = LocalDate.of(2010, 1, 1);
            LocalDate baseDate = LocalDate.of(2026, 6, 1);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.SENIOR_HIGH);
        }

        @Test
        @DisplayName("N-08: 年度またぎのbaseDate（base: 2026-01-15 → 年度は2025年度扱い）")
        void n08_base_date_before_april() {
            // base: 2026-01-15 → 1月なので年度は2025年度 → fiscalStart = 2025-04-01
            // 2006-04-02 生まれ: between(2006-04-02, 2025-04-01) = 18年 → ADULT
            LocalDate birthDate = LocalDate.of(2006, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 1, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            // fiscalStart = 2025-04-01, between(2006-04-02, 2025-04-01) = 18 → ADULT
            assertThat(result).isEqualTo(AgeGroup.ADULT);
        }

        @Test
        @DisplayName("JUNIOR_HIGH: 中学生（12〜14歳）")
        void junior_high() {
            // 2014-04-02 生まれ: 学年基準日(2026-04-01)時点では11年 → ELEMENTARY_UPPER
            // 2013-04-02 生まれ: between = 12 → JUNIOR_HIGH
            LocalDate birthDate = LocalDate.of(2013, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.JUNIOR_HIGH);
        }

        @Test
        @DisplayName("SENIOR_HIGH: 高校生（15〜17歳）")
        void senior_high() {
            // 2010-04-02 生まれ: between(2010-04-02, 2026-04-01) = 15 → SENIOR_HIGH
            LocalDate birthDate = LocalDate.of(2010, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.SENIOR_HIGH);
        }

        @Test
        @DisplayName("ELEMENTARY_UPPER: 小学校高学年（10〜11歳）")
        void elementary_upper() {
            // 2015-04-02 生まれ: between(2015-04-02, 2026-04-01) = 10 → ELEMENTARY_UPPER
            LocalDate birthDate = LocalDate.of(2015, 4, 2);
            LocalDate baseDate = LocalDate.of(2026, 4, 15);

            AgeGroup result = AgeGroupCalculator.calculate(birthDate, baseDate);

            assertThat(result).isEqualTo(AgeGroup.ELEMENTARY_UPPER);
        }
    }

    // ========================================
    // isMinor（未成年判定）
    // ========================================

    @Nested
    @DisplayName("isMinor - 未成年判定（実年齢）")
    class IsMinor {

        @Test
        @DisplayName("N-05: isMinor 境界値 - 17歳364日 → true")
        void n05_17years_364days_is_minor() {
            // 2008-05-20 生まれ: base: 2026-05-19 → 17歳364日 → true
            LocalDate birthDate = LocalDate.of(2008, 5, 20);
            LocalDate baseDate = LocalDate.of(2026, 5, 19);

            boolean result = AgeGroupCalculator.isMinor(birthDate, baseDate);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("N-06: isMinor 境界値 - 18歳ちょうど → false")
        void n06_exactly_18_is_not_minor() {
            // 2008-05-19 生まれ: base: 2026-05-19 → 18歳ちょうど → false
            LocalDate birthDate = LocalDate.of(2008, 5, 19);
            LocalDate baseDate = LocalDate.of(2026, 5, 19);

            boolean result = AgeGroupCalculator.isMinor(birthDate, baseDate);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("18歳1日後 → false（成人）")
        void day_after_18th_birthday_is_not_minor() {
            LocalDate birthDate = LocalDate.of(2008, 5, 18);
            LocalDate baseDate = LocalDate.of(2026, 5, 19);

            boolean result = AgeGroupCalculator.isMinor(birthDate, baseDate);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("17歳11ヶ月 → true（未成年）")
        void seventeen_years_eleven_months_is_minor() {
            LocalDate birthDate = LocalDate.of(2008, 6, 19);
            LocalDate baseDate = LocalDate.of(2026, 5, 19);

            boolean result = AgeGroupCalculator.isMinor(birthDate, baseDate);

            assertThat(result).isTrue();
        }
    }
}
