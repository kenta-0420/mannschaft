package com.mannschaft.app.postal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CountryResolver} の単体テスト。
 *
 * <p>明示国コード優先・locale プレフィックス推定・解決不能の 3 系統を検証する。
 * weather 既存挙動（locale→国 マップ）の回帰防止も兼ねる。</p>
 */
@DisplayName("CountryResolver 単体テスト")
class CountryResolverTest {

    private final CountryResolver resolver = new CountryResolver();

    @Test
    @DisplayName("countryCode が非blank ならそれを大文字で返す（locale より優先）")
    void explicitCountryWins() {
        assertThat(resolver.resolve("US", "ja")).contains("US");
        assertThat(resolver.resolve("jp", "en")).contains("JP");
    }

    @Test
    @DisplayName("countryCode が null なら locale プレフィックスから推定する")
    void localePrefixResolution() {
        // weather 既存挙動の回帰防止: ja→JP, en→US, zh→CN, ko→KR, es→ES, de→DE
        assertThat(resolver.resolve(null, "ja")).contains("JP");
        assertThat(resolver.resolve(null, "ja-JP")).contains("JP");
        assertThat(resolver.resolve(null, "en")).contains("US");
        assertThat(resolver.resolve(null, "zh")).contains("CN");
        assertThat(resolver.resolve(null, "ko")).contains("KR");
        assertThat(resolver.resolve(null, "es")).contains("ES");
        assertThat(resolver.resolve(null, "de-DE")).contains("DE");
    }

    @Test
    @DisplayName("未対応 locale（fr）は空を返す")
    void unsupportedLocaleEmpty() {
        assertThat(resolver.resolve(null, "fr")).isEmpty();
    }

    @Test
    @DisplayName("countryCode も locale も解決できなければ空")
    void nullBothEmpty() {
        assertThat(resolver.resolve(null, null)).isEmpty();
        assertThat(resolver.resolve(null, "")).isEmpty();
        assertThat(resolver.resolve("", "fr")).isEmpty();
    }
}
