package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.service.RanchEggCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** AC47/48: 7日elapsedとひびの境界。時刻だけで孵化書込はしない。 */
class RanchEggCalculatorTest {
    private final RanchEggCalculator calculator = new RanchEggCalculator();
    private final Instant started = Instant.parse("2026-10-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"-1,INTACT", "0,INTACT", "259199,INTACT", "259200,SMALL_CRACK", "431999,SMALL_CRACK", "432000,WIDE_CRACK", "604799,WIDE_CRACK", "604800,READY"})
    void ひびはserverElapsedの境界に従う(long seconds, EggCrackStage expected) {
        assertThat(calculator.crackStage(started, started.plusSeconds(seconds), 259200, 432000, 604800)).isEqualTo(expected);
    }

    @Test
    void 期限直前は未準備で期限丁度から選定済みだけ準備完了() {
        Instant ready = started.plusSeconds(604800);
        assertThat(calculator.hatchReady(ready, started, ready.minusNanos(1000))).isFalse();
        assertThat(calculator.hatchReady(ready, started, ready)).isTrue();
        assertThat(calculator.hatchReady(ready, null, ready.plusSeconds(100))).isFalse();
    }
}
