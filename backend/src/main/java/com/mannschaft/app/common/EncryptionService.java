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
     * 暗号文として復号を試み、当サービスの暗号文フォーマットでない値（暗号化導入前に
     * 平文のまま保存されたレガシーデータ / シードデータ等）はそのまま平文として返す。
     *
     * <p>背景: PII カラムは {@code V9.053} で暗号化対応（VARCHAR → TEXT + Base64 暗号文格納）に
     * 移行したが、システムユーザー（id=1）・退会センチネル（id=0）など一部のシードデータは
     * 平文のまま {@code last_name='システム'} のように挿入されている。これらの行を
     * {@link #decrypt(String)} で素朴に復号すると Base64 デコードに失敗して例外となり、
     * 当該ユーザーを参照する全ての読み取り経路（例: 予定詳細 GET の作成者表示名解決）が
     * 500 を返してしまう。</p>
     *
     * <p>判定方針（症状を隠さない安全な切り分け）:</p>
     * <ul>
     *   <li>当サービスの暗号文は必ず「厳密 Base64」かつ「デコード後 28 バイト以上
     *       （IV 12 + GCM 認証タグ 16 + 平文 0 以上）」になる。この形を満たさない値は
     *       {@link #encrypt(String)} が生成し得ない＝暗号化前のレガシー平文と断定できるため、
     *       そのまま返す。</li>
     *   <li>形は暗号文だが GCM 認証に失敗する値（改竄・鍵不一致など真の異常）は
     *       従来どおり {@link EncryptionException} を送出し、症状を握り潰さない。</li>
     * </ul>
     *
     * @param storedValue DB から読み出した値（null の場合は null を返す）
     * @return 復号された平文、またはレガシー平文の場合はその値そのもの
     */
    public String decryptLegacyAware(String storedValue) {
        if (storedValue == null) {
            return null;
        }
        if (!looksLikeCiphertext(storedValue)) {
            // 当サービスの暗号文フォーマットではない＝暗号化導入前のレガシー平文。
            // そのまま返す（これはレガシー平文の「復号結果」そのものであり、症状隠しではない）。
            return storedValue;
        }
        // 形は暗号文。GCM 認証失敗（改竄・鍵不一致）は EncryptionException として正しく送出する。
        return decrypt(storedValue);
    }

    /**
     * 値が当サービスの暗号文フォーマット（厳密 Base64 かつデコード後 IV+タグ長以上）かを判定する。
     *
     * @param value 判定対象（null 不可）
     * @return 暗号文フォーマットを満たすなら true
     */
    private boolean looksLikeCiphertext(String value) {
        if (value.isEmpty()) {
            return false;
        }
        final byte[] decoded;
        try {
            // getDecoder() は厳密 Base64。レガシー平文（日本語等の非 Base64 文字）はここで弾かれる。
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // encrypt("") でも IV(12) + GCM タグ(16) = 28 バイトになる。これ未満は暗号文ではない。
        return decoded.length >= GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);
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
