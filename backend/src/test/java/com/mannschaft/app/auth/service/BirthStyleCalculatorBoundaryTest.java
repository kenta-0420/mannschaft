package com.mannschaft.app.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 正式出生81へ渡す派生数の1/9境界と11/22/33の通常還元を固定する。計算則は変更しない。 */
class BirthStyleCalculatorBoundaryTest {
    private final BirthStyleCalculator calculator = new BirthStyleCalculator();

    @ParameterizedTest
    @CsvSource({"2000-01-07,10,1", "2000-01-06,9,9", "2000-01-08,11,2", "1990-01-02,22,4", "1999-01-04,33,6"})
    void 出生日の桁和を特別数として残さず1から9へ還元する(String date, int sum, int number) {
        var result = calculator.calculate(LocalDate.parse(date), "イ", "イ");
        assertThat(result.dateSum()).isEqualTo(sum);
        assertThat(result.lifePathNumber()).isEqualTo(number);
        assertThat(result.normalizationVersion()).isEqualTo("nfkc-hepburn-v1");
        assertThat(result.ruleVersion()).isEqualTo("digit-reduce-v1");
    }

    @ParameterizedTest
    @CsvSource({"ア,イ,10,1", "イ,イ,18,9", "アア,イ,11,2", "イイア,ウ,22,4", "ヤマダ,ケンタ,33,6"})
    void 本人カナの桁和も同じ1から9の境界と通常還元を使う(String last, String first, int sum, int number) {
        var result = calculator.calculate(LocalDate.of(2000, 1, 1), last, first);
        assertThat(result.nameSum()).isEqualTo(sum);
        assertThat(result.nameNumber()).isEqualTo(number);
    }

    @Test
    void 欠損DOBと四桁範囲外の年を推測せず拒否する() {
        assertThatThrownBy(() -> calculator.calculate(null, "イ", "イ")).isInstanceOf(IllegalArgumentException.class);
        for (int year : new int[] {0, 10000}) {
            assertThatThrownBy(() -> calculator.calculate(LocalDate.of(year, 1, 1), "イ", "イ"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
