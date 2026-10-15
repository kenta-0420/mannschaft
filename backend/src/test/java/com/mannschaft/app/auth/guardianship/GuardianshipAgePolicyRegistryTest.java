package com.mannschaft.app.auth.guardianship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuardianshipAgePolicyRegistry} テスト（F08.9 P3a）。
 *
 * <p>JP → {@link JapanGuardianshipAgePolicy}、未対応国・null・空 → フォールバック
 * （{@link DefaultGuardianshipAgePolicy}）。国コードは大文字・小文字を吸収する。</p>
 */
@DisplayName("GuardianshipAgePolicyRegistry テスト（F08.9 P3a）")
class GuardianshipAgePolicyRegistryTest {

    private final JapanGuardianshipAgePolicy japanPolicy = new JapanGuardianshipAgePolicy();
    private final DefaultGuardianshipAgePolicy defaultPolicy = new DefaultGuardianshipAgePolicy();

    private GuardianshipAgePolicyRegistry newRegistry() {
        return new GuardianshipAgePolicyRegistry(List.of(japanPolicy, defaultPolicy), defaultPolicy);
    }

    @Test
    @DisplayName("JP → JapanGuardianshipAgePolicy")
    void jp_returnsJapanPolicy() {
        assertThat(newRegistry().forCountry("JP")).isSameAs(japanPolicy);
    }

    @Test
    @DisplayName("小文字 jp も JapanGuardianshipAgePolicy（大文字正規化）")
    void lowercaseJp_returnsJapanPolicy() {
        assertThat(newRegistry().forCountry("jp")).isSameAs(japanPolicy);
    }

    @Test
    @DisplayName("未対応国（US）→ フォールバック（DefaultGuardianshipAgePolicy）")
    void unsupportedCountry_returnsFallback() {
        assertThat(newRegistry().forCountry("US")).isSameAs(defaultPolicy);
    }

    @Test
    @DisplayName("null → フォールバック")
    void nullCountry_returnsFallback() {
        assertThat(newRegistry().forCountry(null)).isSameAs(defaultPolicy);
    }

    @Test
    @DisplayName("空文字 → フォールバック")
    void blankCountry_returnsFallback() {
        assertThat(newRegistry().forCountry("  ")).isSameAs(defaultPolicy);
    }

    @Test
    @DisplayName("フォールバックは supportedCountryCode が null でも索引化されず安全に解決される")
    void fallbackNotIndexed() {
        GuardianshipAgePolicyRegistry registry = newRegistry();
        // DefaultGuardianshipAgePolicy.supportedCountryCode() == null なので国索引には入らない。
        // それでも JP は Japan、未対応はフォールバックに解決される。
        assertThat(registry.forCountry("JP")).isSameAs(japanPolicy);
        assertThat(registry.forCountry("DE")).isSameAs(defaultPolicy);
    }
}
