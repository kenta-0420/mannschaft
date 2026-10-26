package com.mannschaft.app.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlugGenerator ユニットテスト。
 *
 * <p>チーム名・組織名からURL用スラッグを生成する変換ロジックを検証する。</p>
 */
@DisplayName("SlugGenerator")
class SlugGeneratorTest {

    // ========================================
    // generate() テスト
    // ========================================

    @Test
    @DisplayName("英語名から正しいスラッグを生成する")
    void generate_englishName_convertsCorrectly() {
        assertThat(SlugGenerator.generate("FC Tokyo 2023")).isEqualTo("fc-tokyo-2023");
    }

    @Test
    @DisplayName("英語名（My Team ABC）から正しいスラッグを生成する")
    void generate_simpleEnglishName_convertsCorrectly() {
        assertThat(SlugGenerator.generate("My Team ABC")).isEqualTo("my-team-abc");
    }

    @Test
    @DisplayName("日本語のみの名称（ASCII部分が2文字未満）はフォールバックを返す")
    void generate_japaneseOnlyWithShortAscii_returnsFallback() {
        // 鈴木FC → 変換後 "fc" は2文字で MIN_LENGTH(3) 未満のため "team" にフォールバック
        assertThat(SlugGenerator.generate("鈴木FC")).isEqualTo("team");
    }

    @Test
    @DisplayName("日本語のみの名称（ASCII部分なし）はフォールバックを返す")
    void generate_japaneseOnly_returnsFallback() {
        assertThat(SlugGenerator.generate("東京フットボールクラブ")).isEqualTo("team");
    }

    @Test
    @DisplayName("30文字超の名称は30文字に切り詰める")
    void generate_longName_truncatesTo30() {
        String longName = "abcdefghijklmnopqrstuvwxyz12345";
        String slug = SlugGenerator.generate(longName);
        assertThat(slug).hasSize(30);
        assertThat(slug).isEqualTo("abcdefghijklmnopqrstuvwxyz1234");
    }

    @Test
    @DisplayName("null はフォールバック文字列を返す")
    void generate_null_returnsFallback() {
        assertThat(SlugGenerator.generate(null)).isEqualTo("team");
    }

    @Test
    @DisplayName("空文字はフォールバック文字列を返す")
    void generate_emptyString_returnsFallback() {
        assertThat(SlugGenerator.generate("")).isEqualTo("team");
    }

    @Test
    @DisplayName("空白のみはフォールバック文字列を返す")
    void generate_blankString_returnsFallback() {
        assertThat(SlugGenerator.generate("   ")).isEqualTo("team");
    }

    @Test
    @DisplayName("連続スペース・特殊文字は単一ハイフンに圧縮する")
    void generate_specialCharsAndMultipleSpaces_compressToSingleHyphen() {
        assertThat(SlugGenerator.generate("Store  ##  Tokyo")).isEqualTo("store-tokyo");
    }

    @Test
    @DisplayName("先頭・末尾の非英数字は除去される")
    void generate_leadingTrailingSpecialChars_areRemoved() {
        assertThat(SlugGenerator.generate("!!Team Alpha!!")).isEqualTo("team-alpha");
    }

    @Test
    @DisplayName("大文字は小文字に変換される")
    void generate_uppercaseLetters_convertToLowercase() {
        assertThat(SlugGenerator.generate("FC TOKYO")).isEqualTo("fc-tokyo");
    }

    @Test
    @DisplayName("数字を含む名称を正しく変換する")
    void generate_nameWithNumbers_convertsCorrectly() {
        assertThat(SlugGenerator.generate("Team123")).isEqualTo("team123");
    }

    @ParameterizedTest(name = "入力: \"{0}\" → 期待: \"{1}\"")
    @CsvSource({
            "abc,abc",
            "ABC,abc",
            "a b c,a-b-c",
            "a--b,a-b",
            "123,123"
    })
    @DisplayName("各種入力パターンのパラメータテスト")
    void generate_variousInputs(String input, String expected) {
        assertThat(SlugGenerator.generate(input)).isEqualTo(expected);
    }

    // ========================================
    // withSuffix() テスト
    // ========================================

    @Test
    @DisplayName("withSuffix でハイフン付き番号サフィックスを付与する")
    void withSuffix_appendsNumberSuffix() {
        assertThat(SlugGenerator.withSuffix("team-tokyo", 2)).isEqualTo("team-tokyo-2");
    }

    @Test
    @DisplayName("withSuffix は1から始まるサフィックスも正しく付与する")
    void withSuffix_withSuffix1() {
        assertThat(SlugGenerator.withSuffix("fc-osaka", 1)).isEqualTo("fc-osaka-1");
    }

    @Test
    @DisplayName("withSuffix で27文字超のベースは27文字に切り詰める")
    void withSuffix_longBase_truncatesTo27() {
        String longBase = "abcdefghijklmnopqrstuvwxyz12"; // 28文字
        String result = SlugGenerator.withSuffix(longBase, 5);
        // 27文字に切り詰め + "-5" = 29文字（30文字以内）
        assertThat(result).hasSizeLessThanOrEqualTo(30);
        assertThat(result).endsWith("-5");
    }

    @Test
    @DisplayName("withSuffix の結果は常に30文字以内")
    void withSuffix_resultIsWithin30Chars() {
        // 最長ベース（27文字）+ "-9999" = 32文字になる前に切り詰め
        String base = "abcdefghijklmnopqrstuvwxyz1";
        String result = SlugGenerator.withSuffix(base, 9999);
        assertThat(result).hasSizeLessThanOrEqualTo(30);
    }
}
