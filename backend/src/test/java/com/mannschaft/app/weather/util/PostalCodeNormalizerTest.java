package com.mannschaft.app.weather.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostalCodeNormalizer} の単体テスト。
 *
 * <p>F02.10 天気ウィジェットの postal_codes マスタ書き込みと引き当ての両方で
 * 使われる正規化ロジックを網羅検証する。</p>
 */
@DisplayName("PostalCodeNormalizer 単体テスト")
class PostalCodeNormalizerTest {

    @Test
    @DisplayName("JP: 半角ハイフン入りは除去される")
    void jp_removesAsciiHyphen() {
        assertThat(PostalCodeNormalizer.normalize("JP", "490-1401")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("JP: 全角ハイフン（U+2010）も除去される")
    void jp_removesUnicodeHyphen() {
        assertThat(PostalCodeNormalizer.normalize("JP", "490‐1401")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("JP: 長音符（U+30FC）も除去される")
    void jp_removesKatakanaLongSign() {
        assertThat(PostalCodeNormalizer.normalize("JP", "490ー1401")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("JP: 7 桁未満は左ゼロパディング")
    void jp_padsLeftZeroIfShorterThan7() {
        assertThat(PostalCodeNormalizer.normalize("JP", "123")).isEqualTo("0000123");
    }

    @Test
    @DisplayName("JP: 既に正規化済みの値は idempotent（同じ結果）")
    void jp_idempotentForNormalized() {
        assertThat(PostalCodeNormalizer.normalize("JP", "4901401")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("JP: 前後空白はトリムされる")
    void jp_trimsLeadingAndTrailingSpace() {
        assertThat(PostalCodeNormalizer.normalize("JP", "  490-1401  ")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("JP: 小文字 \"jp\" でも判定される（case-insensitive）")
    void jp_caseInsensitiveCountryCode() {
        assertThat(PostalCodeNormalizer.normalize("jp", "490-1401")).isEqualTo("4901401");
    }

    @Test
    @DisplayName("非 JP: トリム + 大文字化のみ（英国の例）")
    void other_trimAndUppercase() {
        assertThat(PostalCodeNormalizer.normalize("GB", "sw1a 1aa")).isEqualTo("SW1A 1AA");
    }

    @Test
    @DisplayName("非 JP: ハイフンは保持される（米国 ZIP+4 など）")
    void other_keepsHyphen() {
        assertThat(PostalCodeNormalizer.normalize("US", "94103-1234")).isEqualTo("94103-1234");
    }

    @Test
    @DisplayName("countryCode が null の場合は other 扱い")
    void nullCountryCode_treatedAsOther() {
        assertThat(PostalCodeNormalizer.normalize(null, "abc123")).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("countryCode が blank の場合は other 扱い")
    void blankCountryCode_treatedAsOther() {
        assertThat(PostalCodeNormalizer.normalize("", "abc123")).isEqualTo("ABC123");
    }
}
