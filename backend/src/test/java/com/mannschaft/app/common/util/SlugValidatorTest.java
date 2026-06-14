package com.mannschaft.app.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SlugValidator} の純粋ユニットテスト（村方式の slug 検証）。
 */
@DisplayName("SlugValidator 単体テスト")
class SlugValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "team-tokyo", "a1b2c3", "my-cool-team-2026", "aaa", "abcdefghij0123456789abcdefghij"})
    @DisplayName("正常な形式は true")
    void 正常な形式(String slug) {
        assertThat(SlugValidator.isValidFormat(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ab",                    // 短すぎ（2文字）
            "-abc",                  // 先頭ハイフン
            "abc-",                  // 末尾ハイフン
            "ab--cd",                // 連続ハイフン
            "Abc",                   // 大文字
            "team_tokyo",            // アンダースコア
            "team tokyo",            // 空白
            "チーム",                // 非ASCII
            "abcdefghij0123456789abcdefghij1"  // 31文字（長すぎ）
    })
    @DisplayName("不正な形式は false")
    void 不正な形式(String slug) {
        assertThat(SlugValidator.isValidFormat(slug)).isFalse();
    }

    @Test
    @DisplayName("null・空は形式不正")
    void nullと空() {
        assertThat(SlugValidator.isValidFormat(null)).isFalse();
        assertThat(SlugValidator.isValidFormat("")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"new", "search", "admin", "settings", "me", "public", "api", "login", "register", "index", "ADMIN", "Search"})
    @DisplayName("予約語は isReserved=true（大文字小文字非依存）")
    void 予約語(String slug) {
        assertThat(SlugValidator.isReserved(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"team-tokyo", "mycoolteam", "abc123"})
    @DisplayName("非予約語は isReserved=false")
    void 非予約語(String slug) {
        assertThat(SlugValidator.isReserved(slug)).isFalse();
    }

    @Test
    @DisplayName("isProvided: null/空白は未指定")
    void isProvided判定() {
        assertThat(SlugValidator.isProvided(null)).isFalse();
        assertThat(SlugValidator.isProvided("")).isFalse();
        assertThat(SlugValidator.isProvided("   ")).isFalse();
        assertThat(SlugValidator.isProvided("abc")).isTrue();
    }
}
