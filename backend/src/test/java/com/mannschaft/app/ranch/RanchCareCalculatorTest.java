package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.service.RanchCareCalculator;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC04/18/64: UTC週枠は同日全回利用でき、残0とBIGINT境界も安全。 */
class RanchCareCalculatorTest {
    private final RanchCareCalculator calculator = new RanchCareCalculator();

    @Test
    void 日曜最終瞬間は旧週に属する() {
        assertThat(calculator.weekStartsOn(Instant.parse("2026-10-04T23:59:59.999999Z")))
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    void 月曜UTC零時から新週に属する() {
        assertThat(calculator.weekStartsOn(Instant.parse("2026-10-05T00:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void 週枠の残量だけを同日に取得し枠後は零になる() {
        long awarded = 0;
        for (int i = 0; i < 6; i++) {
            long gain = calculator.gainedXp(1, 5, awarded);
            assertThat(gain).isEqualTo(i < 5 ? 1 : 0);
            awarded += gain;
        }
        assertThat(awarded).isEqualTo(5);
    }

    @Test
    void 最大BIGINTを加算せず残量を計算する() {
        assertThat(calculator.gainedXp(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1)).isEqualTo(1);
    }

    @Test
    void 不正な量と台帳不整合を握り潰さない() {
        assertThatThrownBy(() -> calculator.gainedXp(0, 5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.gainedXp(1, 5, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 残枠の端数だけを付与して零枠と負台帳を拒否する() {
        assertThat(calculator.gainedXp(3, 5, 4)).isEqualTo(1);
        assertThatThrownBy(() -> calculator.gainedXp(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.gainedXp(1, 5, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
