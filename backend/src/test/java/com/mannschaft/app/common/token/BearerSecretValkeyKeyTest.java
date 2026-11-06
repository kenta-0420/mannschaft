package com.mannschaft.app.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 試練（CMP-260826-1457 W0・AC-28 末尾）: {@link BearerSecretValkeyKey#of(String, String)} の単体テスト。
 *
 * <p>合言葉を Valkey に置くキーは、金庫のハッシュ（SHA-256 hex64）からしか作れないことを固定する。
 * 平文（UUID・Base64URL 43字）を渡す誤用は、番人より先に実行時に例外で止まる。
 * 例外メッセージに渡された値を含めない（平文がログへ流れる経路を作らない・AC-10 の趣旨）。</p>
 *
 * <p>red の理由: W0 試練の時点では {@code of} はスタブで、常に {@link UnsupportedOperationException} を投げる。</p>
 */
@DisplayName("試練 CMP-260826-1457 AC-28: BearerSecretValkeyKey は hex64 のハッシュだけを受け付ける")
class BearerSecretValkeyKeyTest {

    private static final String NAMESPACE = "mannschaft:test:bearer:";

    private final SecretTokenVault vault = new SecretTokenVault();

    @Test
    @DisplayName("金庫のハッシュ（hex64）を渡すと、接頭辞にハッシュを続けたキーを返す")
    void 金庫のハッシュからキーを作れる() {
        String hash = vault.hash(vault.issueBase64Url().plaintext());

        String key = BearerSecretValkeyKey.of(NAMESPACE, hash);

        assertThat(key).isEqualTo(NAMESPACE + hash);
    }

    @Test
    @DisplayName("平文の UUID を渡すと IllegalArgumentException になり、メッセージに平文を含めない")
    void 平文のUUIDは拒否される() {
        String plaintext = UUID.randomUUID().toString();

        assertThatThrownBy(() -> BearerSecretValkeyKey.of(NAMESPACE, plaintext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(plaintext);
    }

    @Test
    @DisplayName("金庫が発行した平文（Base64URL 43字）そのものを渡すと IllegalArgumentException になる")
    void 金庫の平文そのものは拒否される() {
        String plaintext = vault.issueBase64Url().plaintext();

        assertThatThrownBy(() -> BearerSecretValkeyKey.of(NAMESPACE, plaintext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(plaintext);
    }

    @ParameterizedTest(name = "[{index}] 長さや文字種が hex64 でない値: {0}")
    @ValueSource(strings = {
            // 63 文字（1 文字足りない）
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            // 65 文字（1 文字多い）
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            // 64 文字だが 16 進でない文字を含む
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg",
    })
    void hex64でない値は拒否される(String notHex64) {
        assertThatThrownBy(() -> BearerSecretValkeyKey.of(NAMESPACE, notHex64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(notHex64);
    }

    @ParameterizedTest(name = "[{index}] tokenHash が null または空: \"{0}\"")
    @NullAndEmptySource
    void tokenHashがnullや空なら拒否される(String tokenHash) {
        assertThatThrownBy(() -> BearerSecretValkeyKey.of(NAMESPACE, tokenHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "[{index}] 接頭辞が null または空: \"{0}\"")
    @NullAndEmptySource
    void 接頭辞がnullや空なら拒否される(String namespace) {
        String hash = vault.hash(vault.issueBase64Url().plaintext());

        assertThatThrownBy(() -> BearerSecretValkeyKey.of(namespace, hash))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
