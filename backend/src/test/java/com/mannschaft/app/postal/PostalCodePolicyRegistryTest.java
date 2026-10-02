package com.mannschaft.app.postal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostalCodePolicyRegistry} の単体テスト。
 *
 * <p>AC-10 / AC-11 の土台: 「対応国判定」と「生入力フォーマット検証（正規化しない）」が
 * 単一の真実源で正しく効くことを検証する。</p>
 */
@DisplayName("PostalCodePolicyRegistry 単体テスト")
class PostalCodePolicyRegistryTest {

    private final PostalCodePolicyRegistry registry = new PostalCodePolicyRegistry();

    @Nested
    @DisplayName("isValidFormat（JP・生入力 regex・正規化しない）")
    class IsValidFormat {

        // AC-4 / AC-5 / AC-10: JP の正しい形式（ハイフン任意）は valid
        @ParameterizedTest
        @ValueSource(strings = {"123-4567", "1234567", "000-0000"})
        @DisplayName("JP 正常系: 7桁（ハイフン任意）は valid")
        void jpValid(String input) {
            assertThat(registry.isValidFormat("JP", input)).isTrue();
        }

        // AC-4 / AC-10: "111" のような桁不足・不正値は invalid（正規化で救わない）
        @ParameterizedTest
        @ValueSource(strings = {"111", "12-34567", "abcdefg", "12345678", "123-456", "123 4567"})
        @DisplayName("JP 異常系: 桁不足・誤位置ハイフン・英字・桁過多は invalid")
        void jpInvalid(String input) {
            assertThat(registry.isValidFormat("JP", input)).isFalse();
        }

        @Test
        @DisplayName("null / 空文字は invalid")
        void nullOrBlankInvalid() {
            assertThat(registry.isValidFormat("JP", null)).isFalse();
            assertThat(registry.isValidFormat("JP", "")).isFalse();
            assertThat(registry.isValidFormat("JP", "   ")).isFalse();
            assertThat(registry.isValidFormat(null, "123-4567")).isFalse();
        }

        @Test
        @DisplayName("未対応国は常に invalid")
        void unsupportedCountryInvalid() {
            assertThat(registry.isValidFormat("FR", "75001")).isFalse();
        }

        @Test
        @DisplayName("国コードは大文字小文字を問わない")
        void caseInsensitiveCountry() {
            assertThat(registry.isValidFormat("jp", "123-4567")).isTrue();
        }
    }

    @Nested
    @DisplayName("isSupported（対応国判定）")
    class IsSupported {

        // AC-11: 対応国 JP は true、未対応 FR は false
        @Test
        @DisplayName("JP は対応国 true / FR は未対応 false")
        void supportedFlag() {
            assertThat(registry.isSupported("JP")).isTrue();
            assertThat(registry.isSupported("jp")).isTrue();
            assertThat(registry.isSupported("FR")).isFalse();
            assertThat(registry.isSupported("US")).isFalse();
        }

        @Test
        @DisplayName("null / 空文字は false")
        void nullOrBlankFalse() {
            assertThat(registry.isSupported(null)).isFalse();
            assertThat(registry.isSupported("")).isFalse();
        }
    }

    @Nested
    @DisplayName("getPolicy / all")
    class PolicyAccess {

        @Test
        @DisplayName("JP のポリシーを取得できる（example / pattern）")
        void getJpPolicy() {
            Optional<PostalCodePolicy> policy = registry.getPolicy("JP");
            assertThat(policy).isPresent();
            assertThat(policy.get().countryCode()).isEqualTo("JP");
            assertThat(policy.get().example()).isEqualTo("123-4567");
            assertThat(policy.get().pattern()).isEqualTo("^\\d{3}-?\\d{4}$");
        }

        @Test
        @DisplayName("未対応国のポリシーは空")
        void unsupportedPolicyEmpty() {
            assertThat(registry.getPolicy("FR")).isEmpty();
            assertThat(registry.getPolicy(null)).isEmpty();
        }

        @Test
        @DisplayName("all() は対応国（JP）を含む")
        void allContainsJp() {
            assertThat(registry.all())
                    .extracting(PostalCodePolicy::countryCode)
                    .contains("JP");
        }
    }
}
