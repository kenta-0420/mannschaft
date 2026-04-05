package com.mannschaft.app.common;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 暗号化/復号およびHMAC-SHA256ブラインドインデックス生成を提供する。
 * <p>
 * 暗号文フォーマット: Base64(IV[12] + ciphertext + authTag[16])
 */
public class EncryptionService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey encryptionKey;
    private final SecretKey hmacKey;
    private final SecureRandom secureRandom;

    public EncryptionService(byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
        if (encryptionKeyBytes.length != 32) {
            throw new IllegalArgumentException("Encryption key must be 256 bits (32 bytes)");
        }
        if (hmacKeyBytes.length < 32) {
            throw new IllegalArgumentException("HMAC key must be at least 256 bits (32 bytes)");
        }
        this.encryptionKey = new SecretKeySpec(encryptionKeyBytes, "AES");
        this.hmacKey = new SecretKeySpec(hmacKeyBytes, HMAC_SHA256);
        this.secureRandom = new SecureRandom();
    }

    /**
     * 平文をAES-256-GCMで暗号化し、Base64文字列で返す。
     *
     * @param plainText 平文（nullの場合はnullを返す）
     * @return Base64エンコードされた暗号文
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /**
     * Base64暗号文をAES-256-GCMで復号し、平文を返す。
     *
     * @param cipherText Base64エンコードされた暗号文（nullの場合はnullを返す）
     * @return 復号された平文
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length < GCM_IV_LENGTH + 1) {
                throw new EncryptionException("Ciphertext too short to contain IV and data");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);

            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    /**
     * バイト列をAES-256-GCMで暗号化する。
     *
     * @param plainBytes 平文バイト列（nullの場合はnullを返す）
     * @return IV + 暗号文のバイト列
     */
    public byte[] encryptBytes(byte[] plainBytes) {
        if (plainBytes == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherBytes = cipher.doFinal(plainBytes);

            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return combined;
        } catch (Exception e) {
            throw new EncryptionException("Byte encryption failed", e);
        }
    }

    /**
     * バイト列をAES-256-GCMで復号する。
     *
     * @param cipherBytes IV + 暗号文のバイト列（nullの場合はnullを返す）
     * @return 復号された平文バイト列
     */
    public byte[] decryptBytes(byte[] cipherBytes) {
        if (cipherBytes == null) {
            return null;
        }
        try {
            if (cipherBytes.length < GCM_IV_LENGTH + 1) {
                throw new EncryptionException("Ciphertext too short to contain IV and data");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(cipherBytes, 0, iv, 0, GCM_IV_LENGTH);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(cipherBytes, GCM_IV_LENGTH, cipherBytes.length - GCM_IV_LENGTH);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Byte decryption failed", e);
        }
    }

    /**
     * HMAC-SHA256でブラインドインデックスを生成する。
     * 検索用の決定論的ハッシュ。
     *
     * @param value ハッシュ対象の値（nullの場合はnullを返す）
     * @return 16進数文字列（64文字）
     */
    public String hmac(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(hmacKey);
            byte[] hash = mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new EncryptionException("HMAC generation failed", e);
        }
    }

    /**
     * 暗号化処理の例外。
     */
    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message) {
            super(message);
        }

        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
