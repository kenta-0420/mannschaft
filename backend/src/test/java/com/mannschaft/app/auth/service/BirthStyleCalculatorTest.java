package com.mannschaft.app.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

/** AC56: 独自数秘の裁可済みdigit reduceとカナ技術規則の試練。 */
class BirthStyleCalculatorTest {
    private final BirthStyleCalculator calculator = new BirthStyleCalculator();

    @Test @DisplayName("UT56: YYYYMMDD合計33を特別保持せず6へ還元する")
    void 年月日の全八桁を一桁に還元() {
        var value = calculator.calculate(LocalDate.of(1990, 1, 22), "ヤマダ", "ケンタ");
        assertThat(value.dateSum()).isEqualTo(24);
        assertThat(value.lifePathNumber()).isEqualTo(6);
        assertThat(value.nameSum()).isEqualTo(33);
        assertThat(value.nameNumber()).isEqualTo(6);
        var master = calculator.calculate(LocalDate.of(1999, 1, 4), "ヤマダ", "ケンタ");
        assertThat(master.dateSum()).isEqualTo(33);
        assertThat(master.lifePathNumber()).isEqualTo(6);
    }
    @Test @DisplayName("UT56: NFKC・空白除去・かな種別は同じ数に正規化")
    void カナ表記を決定的に正規化() {
        var expected = calculator.calculate(LocalDate.of(2000, 2, 29), "ヤマダ", "ケンタ");
        assertThat(calculator.calculate(LocalDate.of(2000, 2, 29), "　やま だ　", "ｹﾝﾀ")).isEqualTo(expected);
    }
    @Test @DisplayName("UT56: 長音・撥音・促音・外来音を版付きヘボン式へ変換")
    void 版付きヘボン式固定例を検証() {
        assertThat(calculator.romanize("ケンタ")).isEqualTo("KENTA");
        assertThat(calculator.romanize("シンイチ")).isEqualTo("SHINICHI");
        assertThat(calculator.romanize("ユウコ")).isEqualTo("YUUKO");
        assertThat(calculator.romanize("ショウ")).isEqualTo("SHOU");
        assertThat(calculator.romanize("ティ")).isEqualTo("TI");
        assertThat(calculator.romanize("ヴ")).isEqualTo("VU");
        assertThat(calculator.romanize("サッカー")).isEqualTo("SAKKAA");
    }
    @Test @DisplayName("UT56: 漢字の読み・欠損・未定義カナを推測しない")
    void 未解釈の読みを推測せず拒否() {
        for (String invalid : new String[]{null, "", " ", "山田", "YAMADA", "ッ", "ー", "アッ"}) {
            assertThatThrownBy(() -> calculator.romanize(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
