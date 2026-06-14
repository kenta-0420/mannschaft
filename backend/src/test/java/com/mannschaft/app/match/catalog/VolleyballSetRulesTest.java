package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.TeamSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VolleyballSetRules} の純ロジック UT（test-first・sports/04_volleyball.md §4）。
 *
 * <p>セット勝利条件（25 点・最終 15 点・デュース＝2 点差）、セット勝者導出、best-of-5 の
 * 試合決着判定（3 セット先取）、獲得セット数からの試合スコア導出を Spring 非依存で検証する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §4.1 / §4.2 / §4.3
 *   / 01_domain_and_ddl.md §B.1.2 / §D.6</p>
 */
@DisplayName("VolleyballSetRules（デュース・best-of-5・勝敗導出）純ロジック UT")
class VolleyballSetRulesTest {

    @Nested
    @DisplayName("setTarget（通常 25 / 最終 15）")
    class SetTarget {

        @Test
        @DisplayName("通常セットの目標点は 25")
        void normalSetTargetIs25() {
            assertThat(VolleyballSetRules.setTarget(false)).isEqualTo(25);
        }

        @Test
        @DisplayName("最終セット（第 5）の目標点は 15")
        void finalSetTargetIs15() {
            assertThat(VolleyballSetRules.setTarget(true)).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("セット決着判定（デュース＝2 点差・§4.2）")
    class SetDecided {

        @Test
        @DisplayName("25-23 は決着（25 到達かつ 2 点差）")
        void normalSetWonAt25_23() {
            assertThat(VolleyballSetRules.isSetDecided(25, 23, false)).isTrue();
        }

        @Test
        @DisplayName("25-24 は未決着（2 点差なし＝デュース継続）")
        void normalSetNotDecidedAt25_24() {
            assertThat(VolleyballSetRules.isSetDecided(25, 24, false)).isFalse();
        }

        @Test
        @DisplayName("24-24 は未決着（目標未到達かつ 2 点差なし）")
        void deuceNotDecidedAt24_24() {
            assertThat(VolleyballSetRules.isSetDecided(24, 24, false)).isFalse();
        }

        @Test
        @DisplayName("26-24 は決着（デュース後 2 点差・§4.2 の 27-25/16-14 と同型）")
        void deuceDecidedAt26_24() {
            assertThat(VolleyballSetRules.isSetDecided(26, 24, false)).isTrue();
        }

        @Test
        @DisplayName("25-24 → 26-24 で決着（デュースの 24-24 から 2 点差がつくまで継続）")
        void deuceProgression() {
            // 24-24 未決着 → 25-24 未決着 → 26-24 決着
            assertThat(VolleyballSetRules.isSetDecided(24, 24, false)).isFalse();
            assertThat(VolleyballSetRules.isSetDecided(25, 24, false)).isFalse();
            assertThat(VolleyballSetRules.isSetDecided(26, 24, false)).isTrue();
        }

        @Test
        @DisplayName("最終セット 15-13 は決着（15 到達かつ 2 点差）")
        void finalSetWonAt15_13() {
            assertThat(VolleyballSetRules.isSetDecided(15, 13, true)).isTrue();
        }

        @Test
        @DisplayName("最終セット 15-14 は未決着（2 点差なし）")
        void finalSetNotDecidedAt15_14() {
            assertThat(VolleyballSetRules.isSetDecided(15, 14, true)).isFalse();
        }

        @Test
        @DisplayName("最終セット 16-14 は決着（デュース後 2 点差）")
        void finalSetDeuceDecidedAt16_14() {
            assertThat(VolleyballSetRules.isSetDecided(16, 14, true)).isTrue();
        }

        @Test
        @DisplayName("目標未到達の同点近辺（20-19）は未決着")
        void notDecidedBeforeTarget() {
            assertThat(VolleyballSetRules.isSetDecided(20, 19, false)).isFalse();
        }
    }

    @Nested
    @DisplayName("セット勝者導出（winner_side・§4.2）")
    class SetWinner {

        @Test
        @DisplayName("25-23 は HOME 勝ち")
        void homeWinsSet() {
            assertThat(VolleyballSetRules.resolveSetWinner(25, 23, false)).isEqualTo(TeamSide.HOME);
        }

        @Test
        @DisplayName("23-25 は AWAY 勝ち")
        void awayWinsSet() {
            assertThat(VolleyballSetRules.resolveSetWinner(23, 25, false)).isEqualTo(TeamSide.AWAY);
        }

        @Test
        @DisplayName("未決着（25-24）の勝者は null")
        void noWinnerWhenUndecided() {
            assertThat(VolleyballSetRules.resolveSetWinner(25, 24, false)).isNull();
        }
    }

    @Nested
    @DisplayName("best-of-5 試合決着判定（3 セット先取・§4.1）")
    class MatchDecided {

        @Test
        @DisplayName("BEST_OF_5 は 3 セット先取で必要勝ちセット数 3")
        void setsToWinBestOf5() {
            assertThat(VolleyballSetRules.setsToWin("BEST_OF_5")).isEqualTo(3);
        }

        @Test
        @DisplayName("BEST_OF_3 は 2 セット先取")
        void setsToWinBestOf3() {
            assertThat(VolleyballSetRules.setsToWin("BEST_OF_3")).isEqualTo(2);
        }

        @Test
        @DisplayName("未指定（null）は best-of-5 既定で 3")
        void setsToWinDefault() {
            assertThat(VolleyballSetRules.setsToWin(null)).isEqualTo(3);
        }

        @Test
        @DisplayName("3-0 / 3-1 / 3-2 は試合決着（best-of-5）")
        void matchDecidedWhenThreeSetsWon() {
            assertThat(VolleyballSetRules.isMatchDecided(3, 0, "BEST_OF_5")).isTrue();
            assertThat(VolleyballSetRules.isMatchDecided(3, 1, "BEST_OF_5")).isTrue();
            assertThat(VolleyballSetRules.isMatchDecided(3, 2, "BEST_OF_5")).isTrue();
            assertThat(VolleyballSetRules.isMatchDecided(2, 3, "BEST_OF_5")).isTrue();
        }

        @Test
        @DisplayName("2-2 や 2-1 は未決着（どちらも 3 セット先取していない）")
        void matchNotDecidedBelowThreshold() {
            assertThat(VolleyballSetRules.isMatchDecided(2, 2, "BEST_OF_5")).isFalse();
            assertThat(VolleyballSetRules.isMatchDecided(2, 1, "BEST_OF_5")).isFalse();
            assertThat(VolleyballSetRules.isMatchDecided(0, 0, "BEST_OF_5")).isFalse();
        }

        @Test
        @DisplayName("第 5 セットの可否: 2-2 から第 5 セット（最終 15 点）に進む＝5 番目のセットが最終")
        void fifthSetIsFinalWhenTwoTwo() {
            // 1〜4 セット目は通常 25 点・5 セット目のみ最終 15 点
            assertThat(VolleyballSetRules.isFinalSet(5, "BEST_OF_5")).isTrue();
            assertThat(VolleyballSetRules.isFinalSet(4, "BEST_OF_5")).isFalse();
            assertThat(VolleyballSetRules.isFinalSet(1, "BEST_OF_5")).isFalse();
            // best-of-3 では 3 セット目が最終
            assertThat(VolleyballSetRules.isFinalSet(3, "BEST_OF_3")).isTrue();
            assertThat(VolleyballSetRules.isFinalSet(2, "BEST_OF_3")).isFalse();
        }
    }

    @Nested
    @DisplayName("試合勝敗（W/L・引分なし・§4.3 / §B.1.2）")
    class MatchResult {

        @Test
        @DisplayName("3-1 は試合決着かつ引分けでない（COMPLETED 可能条件）")
        void completableWhenDecidedAndNotDraw() {
            assertThat(VolleyballSetRules.isMatchCompletable(3, 1, "BEST_OF_5")).isTrue();
            assertThat(VolleyballSetRules.isMatchCompletable(3, 2, "BEST_OF_5")).isTrue();
        }

        @Test
        @DisplayName("獲得セット数が NULL なら COMPLETED 不可")
        void notCompletableWhenNull() {
            assertThat(VolleyballSetRules.isMatchCompletable(null, 1, "BEST_OF_5")).isFalse();
            assertThat(VolleyballSetRules.isMatchCompletable(3, null, "BEST_OF_5")).isFalse();
        }

        @Test
        @DisplayName("引分け（2-2）は COMPLETED 不可（バレーに D なし）")
        void notCompletableWhenDraw() {
            assertThat(VolleyballSetRules.isMatchCompletable(2, 2, "BEST_OF_5")).isFalse();
        }

        @Test
        @DisplayName("勝者が 3 セット先取に満たない（2-1）なら COMPLETED 不可")
        void notCompletableWhenWinnerBelowThreshold() {
            assertThat(VolleyballSetRules.isMatchCompletable(2, 1, "BEST_OF_5")).isFalse();
        }
    }
}
