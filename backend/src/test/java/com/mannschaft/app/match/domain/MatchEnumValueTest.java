package com.mannschaft.app.match.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.10 コア enum の値域を設計（01 §D.1 / sports/01 §2/§3）に照らして固定する番人テスト。
 *
 * <p>enum 値の追加・削除・改名は設計合意なしに行わない。値域がドリフトすると DDL（VARCHAR 格納値）・
 * フロント・集計の整合が崩れるため、本テストで値域を明示的に固定する。</p>
 */
@DisplayName("F08.10 コア enum 値域テスト")
class MatchEnumValueTest {

    private static java.util.Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("MatchKind は PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE の 4 値")
    void matchKind() {
        assertThat(names(MatchKind.values()))
                .containsExactlyInAnyOrder("PRACTICE", "FRIENDLY", "TOURNAMENT", "LEAGUE");
    }

    @Test
    @DisplayName("TeamSide は HOME/AWAY の 2 値（NEUTRAL は home_away 側）")
    void teamSide() {
        assertThat(names(TeamSide.values()))
                .containsExactlyInAnyOrder("HOME", "AWAY");
    }

    @Test
    @DisplayName("HomeAway は HOME/AWAY/NEUTRAL の 3 値")
    void homeAway() {
        assertThat(names(HomeAway.values()))
                .containsExactlyInAnyOrder("HOME", "AWAY", "NEUTRAL");
    }

    @Test
    @DisplayName("MatchStatus は POSTPONED を含む 5 値（tournament 側と一致・B.1.1）")
    void matchStatus() {
        assertThat(names(MatchStatus.values()))
                .containsExactlyInAnyOrder(
                        "SCHEDULED", "IN_PROGRESS", "COMPLETED", "POSTPONED", "CANCELLED");
    }

    @Test
    @DisplayName("MatchStatus は既存 tournament.FixtureStatus と値域が一致する（B.1.1 照合表）")
    void matchStatusAlignsWithTournament() {
        assertThat(names(MatchStatus.values()))
                .as("fixture 化で tournament status を match 側へ寄せるため値域一致が前提")
                .isEqualTo(names(com.mannschaft.app.tournament.FixtureStatus.values()));
    }

    @Test
    @DisplayName("PeriodType はサッカー値＋多競技拡張値を含む器")
    void periodType() {
        assertThat(names(PeriodType.values()))
                .contains("FIRST_HALF", "SECOND_HALF", "EXTRA_FIRST", "EXTRA_SECOND", "PENALTY_SHOOTOUT")
                .contains("QUARTER_1", "QUARTER_2", "QUARTER_3", "QUARTER_4", "OVERTIME");
    }

    @Test
    @DisplayName("Sport は MVP 6 競技（SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO・01 §D.1）")
    void sport() {
        assertThat(names(Sport.values()))
                .containsExactlyInAnyOrder(
                        "SOCCER", "FUTSAL", "BASKETBALL", "VOLLEYBALL", "SHOGI", "GO");
    }

    @Test
    @DisplayName("StateModel は CONTINUOUS_TIME/SET_BASED/TURN_BASED の 3 類型（01 §D.6）")
    void stateModel() {
        assertThat(names(StateModel.values()))
                .containsExactlyInAnyOrder("CONTINUOUS_TIME", "SET_BASED", "TURN_BASED");
    }

    @Test
    @DisplayName("MatchEventType はサッカーが使う値＋OTHER を含む器（PENALTY_SHOOTOUT 含む）")
    void matchEventType() {
        assertThat(names(MatchEventType.values()))
                .contains("STARTER", "SUB_IN", "SUB_OUT",
                        "GOAL", "ASSIST", "OWN_GOAL", "PENALTY_GOAL", "PENALTY_MISS", "PENALTY_SHOOTOUT",
                        "YELLOW_CARD", "RED_CARD", "SECOND_YELLOW",
                        "SAVE", "INJURY", "PERIOD_START", "PERIOD_END", "OTHER");
    }
}
