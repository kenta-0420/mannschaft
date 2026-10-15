package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdempotencyKeyGenerator} の単体テスト。
 */
@DisplayName("IdempotencyKeyGenerator 単体テスト")
class IdempotencyKeyGeneratorTest {

    private final IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator();

    @Test
    @DisplayName("同じ入力なら同じキーが生成される (決定論性)")
    void sameInputProducesSameKey() {
        String key1 = generator.generate(123L, "VERIFICATION", "token-abc");
        String key2 = generator.generate(123L, "VERIFICATION", "token-abc");
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("異なる nonce で異なるキーが生成される")
    void differentNonceProducesDifferentKey() {
        String key1 = generator.generate(123L, "VERIFICATION", "token-abc");
        String key2 = generator.generate(123L, "VERIFICATION", "token-xyz");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("userId が null でも生成できる (\"0\" を使用)")
    void nullUserIdUsesZero() {
        String keyNull = generator.generate(null, "VERIFICATION", "nonce");
        String keyZero = generator.generate(0L, "VERIFICATION", "nonce");
        assertThat(keyNull).isEqualTo(keyZero);
    }

    @Test
    @DisplayName("出力は常に 32 文字の 16 進文字列")
    void outputIsAlways32HexChars() {
        String key = generator.generate(42L, "PASSWORD_RESET", "abc");
        assertThat(key).hasSize(32);
        assertThat(key).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("nonce が null でも生成できる (UUID フォールバック)")
    void nullNonceUsesUuidFallback() {
        String key = generator.generate(1L, "VERIFICATION", null);
        assertThat(key).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("nonce が null の場合は呼び出しごとに違うキーになる (UUID ランダム性)")
    void nullNonceProducesDifferentKeysEachCall() {
        String key1 = generator.generate(1L, "VERIFICATION", null);
        String key2 = generator.generate(1L, "VERIFICATION", null);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("templateKind が異なれば異なるキーになる")
    void differentTemplateKindProducesDifferentKey() {
        String k1 = generator.generate(1L, "VERIFICATION", "n");
        String k2 = generator.generate(1L, "PASSWORD_RESET", "n");
        assertThat(k1).isNotEqualTo(k2);
    }
}
