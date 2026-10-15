package com.mannschaft.app.match.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sport → StateModel マッピングの番人テスト（F08.10 6-① / 01 §D.6）。
 *
 * <p>多競技の中核機構＝「競技を 3 つの状態モデル類型に抽象化」する正準マッピングを固定する。
 * 6 競技すべての所属類型がドリフトすると、出場時間算出スキップ・COMPLETED バリデーション・
 * FE composable 選択の分岐が全競技横断で崩れるため、本テストで明示的に固定する。</p>
 */
@DisplayName("F08.10 Sport→StateModel マッピングテスト（01 §D.6）")
class SportStateModelMappingTest {

    private static java.util.Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Sport は 8 競技（既存 6＋採点競技 FIGURE_SKATING/GYMNASTICS・01 §D.8）")
    void sportHasEightSports() {
        assertThat(names(Sport.values()))
                .containsExactlyInAnyOrder(
                        "SOCCER", "FUTSAL", "BASKETBALL", "VOLLEYBALL", "SHOGI", "GO",
                        "FIGURE_SKATING", "GYMNASTICS");
    }

    @Test
    @DisplayName("StateModel は CONTINUOUS_TIME/SET_BASED/TURN_BASED/SCORED の 4 類型（§D.8）")
    void stateModelHasFourTypes() {
        assertThat(names(StateModel.values()))
                .containsExactlyInAnyOrder("CONTINUOUS_TIME", "SET_BASED", "TURN_BASED", "SCORED");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SOCCER,         CONTINUOUS_TIME",
            "FUTSAL,         CONTINUOUS_TIME",
            "BASKETBALL,     CONTINUOUS_TIME",
            "VOLLEYBALL,     SET_BASED",
            "SHOGI,          TURN_BASED",
            "GO,             TURN_BASED",
            "FIGURE_SKATING, SCORED",
            "GYMNASTICS,     SCORED"
    })
    @DisplayName("8 競技すべてが設計どおりの状態モデル類型に属する")
    void everySportMapsToExpectedStateModel(Sport sport, StateModel expected) {
        assertThat(sport.stateModel()).isEqualTo(expected);
    }

    @Test
    @DisplayName("すべての Sport が非 null の StateModel を返す（マッピング漏れ防止）")
    void everySportHasStateModel() {
        for (Sport sport : Sport.values()) {
            assertThat(sport.stateModel())
                    .as("%s に StateModel マッピングが無い", sport)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("PeriodType はセット制の SET_1..SET_5 を含む（バレー・01 §D.1）")
    void periodTypeHasSetValues() {
        assertThat(names(PeriodType.values()))
                .contains("SET_1", "SET_2", "SET_3", "SET_4", "SET_5");
    }
}
