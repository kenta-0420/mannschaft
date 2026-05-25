package com.mannschaft.app.resume.service;

import com.mannschaft.app.resume.entity.ResumeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResumeEraFormatter} 単体テスト — UT-04（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §7.2 / §12.1 UT-04
 *
 * <p>対象テストケース:
 * <ul>
 *   <li>西暦フォーマット（月あり / 年のみ）</li>
 *   <li>和暦フォーマット（通常年）</li>
 *   <li>改元境界年（1989-01-07 昭和 / 1989-01-08 平成、2019-04-30 平成 / 2019-05-01 令和）</li>
 *   <li>改元年で月 NULL のときの決定的フォールバック（1989→平成元年 / 2019→令和元年）</li>
 * </ul>
 */
@DisplayName("ResumeEraFormatter 単体テスト（UT-04）")
class ResumeEraFormatterTest {

    /** テスト対象（Spring Bean ではなく直接 new）。 */
    private final ResumeEraFormatter formatter = new ResumeEraFormatter();

    // =========================================================================
    // 西暦フォーマット
    // =========================================================================

    @Nested
    @DisplayName("西暦（WESTERN）フォーマット")
    class WesternFormat {

        @Test
        @DisplayName("2018年4月 → \"2018年4月\"")
        void testWestern_2018April() {
            String result = formatter.formatYearMonth(2018, 4, ResumeEntity.EraFormat.WESTERN);
            assertThat(result).isEqualTo("2018年4月");
        }

        @Test
        @DisplayName("月が null の場合 → \"2018年\"（年のみ）")
        void testWestern_yearOnly() {
            String result = formatter.formatYearMonth(2018, null, ResumeEntity.EraFormat.WESTERN);
            assertThat(result).isEqualTo("2018年");
        }

        @Test
        @DisplayName("formatYear（年のみ） → \"2018年\"")
        void testWestern_formatYear() {
            String result = formatter.formatYear(2018, ResumeEntity.EraFormat.WESTERN);
            assertThat(result).isEqualTo("2018年");
        }
    }

    // =========================================================================
    // 和暦フォーマット（通常年）
    // =========================================================================

    @Nested
    @DisplayName("和暦（JAPANESE）フォーマット — 通常年")
    class JapaneseFormatNormal {

        @Test
        @DisplayName("2018年4月 → \"平成30年4月\"")
        void testJapanese_2018April() {
            String result = formatter.formatYearMonth(2018, 4, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成30年4月");
        }

        @Test
        @DisplayName("2022年6月 → \"令和4年6月\"")
        void testJapanese_2022June() {
            String result = formatter.formatYearMonth(2022, 6, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("令和4年6月");
        }

        @Test
        @DisplayName("1990年4月 → \"平成2年4月\"（平成通常年）")
        void testJapanese_1990_Heisei() {
            String result = formatter.formatYearMonth(1990, 4, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成2年4月");
        }

        @Test
        @DisplayName("1987年3月 → \"昭和62年3月\"（昭和通常年）")
        void testJapanese_1987_Showa() {
            String result = formatter.formatYearMonth(1987, 3, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("昭和62年3月");
        }
    }

    // =========================================================================
    // 改元境界年（月あり）
    // =========================================================================

    @Nested
    @DisplayName("改元境界年（月あり）")
    class EraTransitionWithMonth {

        @Test
        @DisplayName("1989年1月7日（昭和最終日） → \"昭和64年1月\"")
        void testJapanese_1989Jan7_Showa() {
            // 月で判定: 1月（1/1〜1/7 が昭和）
            // → ただし月単位では「1月」として昭和/平成どちらかは日付に依存するため
            //   1989年1月を明示的に確認。JapaneseDate(1989,1,1)は昭和64年1月
            String result = formatter.formatYearMonth(1989, 1, ResumeEntity.EraFormat.JAPANESE);
            // 1989年1月は昭和64年（1月7日まで昭和、1月8日から平成。月単位では昭和64年1月）
            assertThat(result).isEqualTo("昭和64年1月");
        }

        @Test
        @DisplayName("1989年2月（平成元年）→ \"平成元年2月\"")
        void testJapanese_1989Jan8_Heisei() {
            // 1989-01-08 以降は平成。2月は確実に平成元年
            String result = formatter.formatYearMonth(1989, 2, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成元年2月");
        }

        @Test
        @DisplayName("2019年4月（平成31年）→ \"平成31年4月\"")
        void testJapanese_2019Apr30_Heisei() {
            // 2019年4月は平成31年（5月1日から令和）
            String result = formatter.formatYearMonth(2019, 4, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成31年4月");
        }

        @Test
        @DisplayName("2019年5月（令和元年）→ \"令和元年5月\"")
        void testJapanese_2019May1_Reiwa() {
            // 2019-05-01 以降は令和
            String result = formatter.formatYearMonth(2019, 5, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("令和元年5月");
        }

        @Test
        @DisplayName("2019年6月 → \"令和元年6月\"")
        void testJapanese_2019June_Reiwa() {
            String result = formatter.formatYearMonth(2019, 6, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("令和元年6月");
        }
    }

    // =========================================================================
    // 改元年で月 NULL のフォールバック
    // =========================================================================

    @Nested
    @DisplayName("改元年・月 NULL のフォールバック規則（§7.2）")
    class EraTransitionNullMonth {

        @Test
        @DisplayName("1989年・月 NULL → \"平成元年\"（多数派の平成を採用）")
        void testJapanese_1989_nullMonth_Heisei() {
            // 1989年: 昭和7日 < 平成358日 → 多数派の平成
            String result = formatter.formatYearMonth(1989, null, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成元年");
        }

        @Test
        @DisplayName("2019年・月 NULL → \"令和元年\"（多数派の令和を採用）")
        void testJapanese_2019_nullMonth_Reiwa() {
            // 2019年: 平成120日 < 令和245日 → 多数派の令和
            String result = formatter.formatYearMonth(2019, null, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("令和元年");
        }

        @Test
        @DisplayName("通常年（1990年）・月 NULL → \"平成2年\"")
        void testJapanese_1990_nullMonth_normal() {
            // 通常年: 月 null → 1月として処理。1990年1月 = 平成2年1月 → 「平成2年」（月なし）
            String result = formatter.formatYearMonth(1990, null, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("平成2年");
        }

        @Test
        @DisplayName("2022年・月 NULL → \"令和4年\"")
        void testJapanese_2022_nullMonth_reiwa() {
            String result = formatter.formatYearMonth(2022, null, ResumeEntity.EraFormat.JAPANESE);
            assertThat(result).isEqualTo("令和4年");
        }
    }
}
