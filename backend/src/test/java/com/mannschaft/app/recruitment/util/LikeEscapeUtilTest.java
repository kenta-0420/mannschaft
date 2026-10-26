package com.mannschaft.app.recruitment.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LikeEscapeUtil} の単体テスト。
 *
 * <p>LIKE ワイルドカード（{@code %} / {@code _}）とエスケープ文字（{@code \}）が
 * リテラル化されること、適用順（バックスラッシュ先行二重化）が正しいこと、null 透過を検証する。</p>
 */
@DisplayName("LikeEscapeUtil 単体テスト")
class LikeEscapeUtilTest {

    @Test
    @DisplayName("null はそのまま null を返す（エスケープしない）")
    void nullPassesThrough() {
        assertThat(LikeEscapeUtil.escape(null)).isNull();
    }

    @Test
    @DisplayName("ワイルドカードを含まない文字列は変化しない")
    void plainStringUnchanged() {
        assertThat(LikeEscapeUtil.escape("hello")).isEqualTo("hello");
        assertThat(LikeEscapeUtil.escape("")).isEqualTo("");
        assertThat(LikeEscapeUtil.escape("日本語テスト")).isEqualTo("日本語テスト");
    }

    @Test
    @DisplayName("% はエスケープされる")
    void percentIsEscaped() {
        assertThat(LikeEscapeUtil.escape("100%")).isEqualTo("100\\%");
        assertThat(LikeEscapeUtil.escape("%%")).isEqualTo("\\%\\%");
    }

    @Test
    @DisplayName("_ はエスケープされる")
    void underscoreIsEscaped() {
        assertThat(LikeEscapeUtil.escape("A_B")).isEqualTo("A\\_B");
    }

    @Test
    @DisplayName("バックスラッシュは二重化される")
    void backslashIsDoubled() {
        assertThat(LikeEscapeUtil.escape("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    @DisplayName("バックスラッシュを先に二重化してから % / _ を前置する（順序の正しさ）")
    void backslashEscapedBeforeWildcards() {
        // 入力: "\%"（バックスラッシュ + パーセント）
        // 期待: "\\" + "\%"  → "\\\%"（バックスラッシュ二重化 + パーセントエスケープ）
        // 順序を誤ると % 前置で挿入した \ を二重化してしまい誤った結果になる。
        assertThat(LikeEscapeUtil.escape("\\%")).isEqualTo("\\\\\\%");
    }

    @Test
    @DisplayName("複合: % / _ / \\ が混在しても全てリテラル化される")
    void mixedSpecialCharacters() {
        assertThat(LikeEscapeUtil.escape("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }
}
