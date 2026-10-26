package com.mannschaft.app.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthConfig#passwordEncoder()} の単体テスト。
 *
 * <p>Argon2id 段階移行の中核となる {@code DelegatingPasswordEncoder} の挙動を検証する。
 * 既存ユーザー（{@code {id}} プレフィックスなしの生 BCrypt ハッシュ）をログイン不能に
 * しないことを担保する重要テスト。</p>
 */
@DisplayName("AuthConfig パスワードエンコーダー（Argon2id 段階移行）")
class AuthConfigTest {

    private final PasswordEncoder encoder = new AuthConfig().passwordEncoder();

    @Test
    @DisplayName("新規エンコードは {argon2} プレフィックス付きで生成される")
    void encode_新規は_argon2プレフィックス() {
        String encoded = encoder.encode("Password1!");

        assertThat(encoded).startsWith("{argon2}");
        assertThat(encoder.matches("Password1!", encoded)).isTrue();
        assertThat(encoder.matches("WrongPassword", encoded)).isFalse();
    }

    @Test
    @DisplayName("既存の生BCryptハッシュ（プレフィックスなし）で検証できる")
    void matches_既存生BCrypt_検証可能() {
        // DB に保存されている既存ハッシュを模倣（{id} プレフィックスなしの生 BCrypt）
        String legacyBcryptHash = new BCryptPasswordEncoder(12).encode("Password1!");
        assertThat(legacyBcryptHash).doesNotStartWith("{");

        // setDefaultPasswordEncoderForMatches により BCrypt として検証される
        assertThat(encoder.matches("Password1!", legacyBcryptHash)).isTrue();
        assertThat(encoder.matches("WrongPassword", legacyBcryptHash)).isFalse();
    }

    @Test
    @DisplayName("既存BCryptハッシュは upgradeEncoding=true（再ハッシュ対象）")
    void upgradeEncoding_既存BCrypt_true() {
        String legacyBcryptHash = new BCryptPasswordEncoder(12).encode("Password1!");

        // 生 BCrypt（{id} なし）は旧アルゴリズム扱い → 再ハッシュ対象
        assertThat(encoder.upgradeEncoding(legacyBcryptHash)).isTrue();
    }

    @Test
    @DisplayName("Argon2idハッシュは upgradeEncoding=false（再ハッシュ不要）")
    void upgradeEncoding_Argon2id_false() {
        String argon2Hash = encoder.encode("Password1!");

        // 既定アルゴリズム（Argon2id）は再ハッシュ不要
        assertThat(encoder.upgradeEncoding(argon2Hash)).isFalse();
    }
}
