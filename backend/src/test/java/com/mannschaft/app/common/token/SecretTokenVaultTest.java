package com.mannschaft.app.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SecretTokenVault} の単体テスト。
 */
class SecretTokenVaultTest {

    private final SecretTokenVault vault = new SecretTokenVault();

    @Test
    @DisplayName("発行されたトークンは毎回異なる（10000回で重複なし）")
    void issue_generatesUniqueTokens() {
        Set<String> plaintexts = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            IssuedToken token = vault.issue();
            plaintexts.add(token.plaintext());
            hashes.add(token.hash());
        }
        assertThat(plaintexts).hasSize(10_000);
        assertThat(hashes).hasSize(10_000);
    }

    @Test
    @DisplayName("平文からハッシュを再計算すると発行時のハッシュと一致する")
    void hash_recomputationMatchesIssuedHash() {
        IssuedToken token = vault.issue();
        assertThat(vault.hash(token.plaintext())).isEqualTo(token.hash());
        assertThat(vault.matches(token.plaintext(), token.hash())).isTrue();
    }

    @Test
    @DisplayName("1文字違う平文からは一致しないハッシュが出る")
    void hash_oneCharacterDifferenceProducesDifferentHash() {
        IssuedToken token = vault.issue();
        String plaintext = token.plaintext();
        char head = plaintext.charAt(0);
        char replaced = (head == '0') ? '1' : '0';
        String tampered = replaced + plaintext.substring(1);

        assertThat(tampered).isNotEqualTo(plaintext);
        assertThat(vault.hash(tampered)).isNotEqualTo(token.hash());
        assertThat(vault.matches(tampered, token.hash())).isFalse();
    }

    @Test
    @DisplayName("平文もハッシュも hex64 形式である")
    void issue_producesHex64() {
        for (int i = 0; i < 100; i++) {
            IssuedToken token = vault.issue();
            assertThat(token.plaintext()).matches("^[0-9a-f]{64}$");
            assertThat(token.hash()).matches("^[0-9a-f]{64}$");
        }
    }

    @Test
    @DisplayName("平文は戻り値以外のどこにも保持されない（部品が状態として抱え込まない）")
    void vault_doesNotRetainPlaintextAsState() throws IllegalAccessException {
        IssuedToken token = vault.issue();

        for (Field field : SecretTokenVault.class.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(vault);
            if (value instanceof CharSequence sequence) {
                assertThat(sequence.toString()).doesNotContain(token.plaintext());
            }
            assertThat(String.valueOf(value)).doesNotContain(token.plaintext());
        }
        // 参照型フィールドは乱数生成器のみで、文字列状態を持たないこと
        assertThat(SecretTokenVault.class.getDeclaredFields())
                .noneMatch(f -> !f.getType().isPrimitive()
                        && CharSequence.class.isAssignableFrom(f.getType())
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers()));
    }

    @Test
    @DisplayName("定数時間比較は同値で true・非同値で false を返す")
    void constantTimeEquals_behavesAsEquality() {
        assertThat(vault.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(vault.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(vault.constantTimeEquals("abc", "abcd")).isFalse();
        assertThat(vault.constantTimeEquals(null, "abc")).isFalse();
        assertThat(vault.constantTimeEquals("abc", null)).isFalse();
    }

    @Test
    @DisplayName("null 平文のハッシュ化は拒否され、照合は false を返す")
    void nullHandling() {
        assertThatThrownBy(() -> vault.hash(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(vault.matches(null, "x")).isFalse();
        assertThat(vault.matches("x", null)).isFalse();
    }
}
