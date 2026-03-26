package com.mannschaft.app.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EncryptionService} の単体テスト。
 * AES-256-GCM暗号化/復号およびHMAC-SHA256ブラインドインデックス生成を検証する。
 */
@DisplayName("EncryptionService 単体テスト")
class EncryptionServiceTest {

    private EncryptionService encryptionService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String TEST_PLAIN_TEXT = "テスト用の平文データ";

    @BeforeEach
    void setUp() {
        byte[] encKey = new byte[32];
        byte[] hmacKey = new byte[32];
        new SecureRandom().nextBytes(encKey);
        new SecureRandom().nextBytes(hmacKey);
        encryptionService = new EncryptionService(encKey, hmacKey);
    }

    // ========================================
    // コンストラクタ
    // ========================================

    @Nested
    @DisplayName("コンストラクタ")
    class Constructor {

        @Test
        @DisplayName("異常系: 暗号化キーが32バイト未満で例外")
        void コンストラクタ_暗号化キー短すぎ_例外() {
            // Given
            byte[] shortKey = new byte[16];
            byte[] hmacKey = new byte[32];

            // When / Then
            assertThatThrownBy(() -> new EncryptionService(shortKey, hmacKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("256 bits");
        }

        @Test
        @DisplayName("異常系: HMACキーが32バイト未満で例外")
        void コンストラクタ_HMACキー短すぎ_例外() {
            // Given
            byte[] encKey = new byte[32];
            byte[] shortHmacKey = new byte[16];

            // When / Then
            assertThatThrownBy(() -> new EncryptionService(encKey, shortHmacKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("256 bits");
        }
    }

    // ========================================
    // encrypt / decrypt（文字列）
    // ========================================

    @Nested
    @DisplayName("encrypt / decrypt")
    class EncryptDecrypt {

        @Test
        @DisplayName("正常系: 暗号化→復号で元の平文に戻る")
        void encrypt_復号_元の平文に戻る() {
            // Given
            String plainText = TEST_PLAIN_TEXT;

            // When
            String encrypted = encryptionService.encrypt(plainText);
            String decrypted = encryptionService.decrypt(encrypted);

            // Then
            assertThat(decrypted).isEqualTo(plainText);
        }

        @Test
        @DisplayName("正常系: 暗号文は平文と異なる")
        void encrypt_暗号文は平文と異なる() {
            // Given
            String plainText = TEST_PLAIN_TEXT;

            // When
            String encrypted = encryptionService.encrypt(plainText);

            // Then
            assertThat(encrypted).isNotEqualTo(plainText);
        }

        @Test
        @DisplayName("正常系: 同じ平文でも暗号文は毎回異なる（IVランダム化）")
        void encrypt_同じ平文_暗号文は毎回異なる() {
            // Given
            String plainText = TEST_PLAIN_TEXT;

            // When
            String encrypted1 = encryptionService.encrypt(plainText);
            String encrypted2 = encryptionService.encrypt(plainText);

            // Then
            assertThat(encrypted1).isNotEqualTo(encrypted2);
        }

        @Test
        @DisplayName("境界値: null入力でnullを返す（encrypt）")
        void encrypt_null入力_nullを返す() {
            // When
            String result = encryptionService.encrypt(null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("境界値: null入力でnullを返す（decrypt）")
        void decrypt_null入力_nullを返す() {
            // When
            String result = encryptionService.decrypt(null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("境界値: 空文字の暗号化→復号")
        void encrypt_空文字_復号で空文字に戻る() {
            // Given
            String plainText = "";

            // When
            String encrypted = encryptionService.encrypt(plainText);
            String decrypted = encryptionService.decrypt(encrypted);

            // Then
            assertThat(decrypted).isEmpty();
        }

        @Test
        @DisplayName("異常系: 不正な暗号文でEncryptionException")
        void decrypt_不正な暗号文_EncryptionException() {
            // Given
            String invalidCipher = java.util.Base64.getEncoder().encodeToString("short".getBytes());

            // When / Then
            assertThatThrownBy(() -> encryptionService.decrypt(invalidCipher))
                    .isInstanceOf(EncryptionService.EncryptionException.class);
        }

        @Test
        @DisplayName("異常系: 改竄された暗号文でEncryptionException")
        void decrypt_改竄された暗号文_EncryptionException() {
            // Given
            String encrypted = encryptionService.encrypt(TEST_PLAIN_TEXT);
            byte[] decoded = java.util.Base64.getDecoder().decode(encrypted);
            decoded[decoded.length - 1] ^= 0xFF; // 末尾のauth tagを改竄
            String tampered = java.util.Base64.getEncoder().encodeToString(decoded);

            // When / Then
            assertThatThrownBy(() -> encryptionService.decrypt(tampered))
                    .isInstanceOf(EncryptionService.EncryptionException.class);
        }
    }

    // ========================================
    // encryptBytes / decryptBytes
    // ========================================

    @Nested
    @DisplayName("encryptBytes / decryptBytes")
    class EncryptDecryptBytes {

        @Test
        @DisplayName("正常系: バイト列の暗号化→復号で元に戻る")
        void encryptBytes_復号_元のバイト列に戻る() {
            // Given
            byte[] plainBytes = TEST_PLAIN_TEXT.getBytes(StandardCharsets.UTF_8);

            // When
            byte[] encrypted = encryptionService.encryptBytes(plainBytes);
            byte[] decrypted = encryptionService.decryptBytes(encrypted);

            // Then
            assertThat(decrypted).isEqualTo(plainBytes);
        }

        @Test
        @DisplayName("境界値: null入力でnullを返す（encryptBytes）")
        void encryptBytes_null入力_nullを返す() {
            // When
            byte[] result = encryptionService.encryptBytes(null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("境界値: null入力でnullを返す（decryptBytes）")
        void decryptBytes_null入力_nullを返す() {
            // When
            byte[] result = encryptionService.decryptBytes(null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: 短すぎるバイト列でEncryptionException")
        void decryptBytes_短すぎるバイト列_EncryptionException() {
            // Given
            byte[] tooShort = new byte[5];

            // When / Then
            assertThatThrownBy(() -> encryptionService.decryptBytes(tooShort))
                    .isInstanceOf(EncryptionService.EncryptionException.class);
        }

        @Test
        @DisplayName("異常系: 改竄されたバイト列でEncryptionException")
        void decryptBytes_改竄されたバイト列_EncryptionException() {
            // Given
            byte[] plainBytes = "test data".getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = encryptionService.encryptBytes(plainBytes);
            encrypted[encrypted.length - 1] ^= 0xFF;

            // When / Then
            assertThatThrownBy(() -> encryptionService.decryptBytes(encrypted))
                    .isInstanceOf(EncryptionService.EncryptionException.class);
        }
    }

    // ========================================
    // hmac
    // ========================================

    @Nested
    @DisplayName("hmac")
    class Hmac {

        @Test
        @DisplayName("正常系: 同じ入力で同じハッシュが返る（決定論的）")
        void hmac_同じ入力_同じハッシュ() {
            // Given
            String value = "test@example.com";

            // When
            String hash1 = encryptionService.hmac(value);
            String hash2 = encryptionService.hmac(value);

            // Then
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("正常系: 異なる入力で異なるハッシュが返る")
        void hmac_異なる入力_異なるハッシュ() {
            // Given
            String value1 = "test1@example.com";
            String value2 = "test2@example.com";

            // When
            String hash1 = encryptionService.hmac(value1);
            String hash2 = encryptionService.hmac(value2);

            // Then
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("正常系: 64文字の16進数文字列を返す")
        void hmac_結果は64文字の16進数() {
            // Given
            String value = "test";

            // When
            String hash = encryptionService.hmac(value);

            // Then
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("境界値: null入力でnullを返す")
        void hmac_null入力_nullを返す() {
            // When
            String result = encryptionService.hmac(null);

            // Then
            assertThat(result).isNull();
        }
    }
}
