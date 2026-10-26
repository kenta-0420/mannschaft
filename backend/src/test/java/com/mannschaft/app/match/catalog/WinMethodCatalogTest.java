package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WinMethodCatalog} の競技別ディスパッチ検証 UT（test-first・01 §D.7 / sports/05・06 §4.1）。
 *
 * <p>将棋/囲碁の勝ち方列挙値の検証・競技間流用の拒否・球技への付与拒否・NULL は常に OK を検証する。</p>
 */
@DisplayName("WinMethodCatalog（勝ち方の競技別検証）UT")
class WinMethodCatalogTest {

    @Test
    @DisplayName("NULL は常に OK（引き分け・任意・後から補完可能）")
    void nullは常にOK() {
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, null)).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.GO, null)).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.SOCCER, null)).isTrue();
        assertThat(WinMethodCatalog.isValid(null, null)).isTrue();
    }

    @Test
    @DisplayName("将棋: 投了/詰み/千日手/持将棋/不戦勝 が OK")
    void 将棋の勝ち方はOK() {
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "RESIGNATION")).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "CHECKMATE")).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "REPETITION")).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "IMPASSE")).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "DEFAULT_WIN")).isTrue();
    }

    @Test
    @DisplayName("囲碁: 目数差勝ち POINTS_WIN が OK")
    void 囲碁の目数差勝ちはOK() {
        assertThat(WinMethodCatalog.isValid(Sport.GO, "POINTS_WIN")).isTrue();
        assertThat(WinMethodCatalog.isValid(Sport.GO, "RESIGNATION")).isTrue();
    }

    @Test
    @DisplayName("競技間の流用は弾く（囲碁に将棋の千日手・将棋に囲碁の目数差勝ち）")
    void 競技間流用は弾く() {
        // 囲碁は千日手（REPETITION）を持たない
        assertThat(WinMethodCatalog.isValid(Sport.GO, "REPETITION")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.GO, "IMPASSE")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.GO, "CHECKMATE")).isFalse();
        // 将棋は目数差勝ち（POINTS_WIN）を持たない
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "POINTS_WIN")).isFalse();
    }

    @Test
    @DisplayName("列挙外の文字列は弾く（400 の温床を排除）")
    void 列挙外は弾く() {
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "NOT_A_METHOD")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.GO, "")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.SHOGI, "resignation")).isFalse(); // 大小区別
    }

    @Test
    @DisplayName("球技に win_method を付与すると弾く（NULL のみ許容）")
    void 球技への付与は弾く() {
        assertThat(WinMethodCatalog.isValid(Sport.SOCCER, "RESIGNATION")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.FUTSAL, "RESIGNATION")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.BASKETBALL, "POINTS_WIN")).isFalse();
        assertThat(WinMethodCatalog.isValid(Sport.VOLLEYBALL, "RESIGNATION")).isFalse();
    }

    @Test
    @DisplayName("競技不明（NULL）に NULL 以外は弾く")
    void 競技不明は弾く() {
        assertThat(WinMethodCatalog.isValid(null, "RESIGNATION")).isFalse();
    }
}
