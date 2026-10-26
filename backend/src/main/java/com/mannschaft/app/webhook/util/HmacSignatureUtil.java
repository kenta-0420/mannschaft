package com.mannschaft.app.webhook.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256署名ユーティリティ。
 * Outgoing Webhookのペイロード署名・検証を担う。
 */
@Component
public class HmacSignatureUtil {

    /** 署名アルゴリズム */
    private static final String ALGORITHM = "HmacSHA256";

    /** 署名プレフィックス */
    private static final String PREFIX = "sha256=";

    /**
     * HMAC-SHA256でペイロードに署名し、"sha256=" + HexString の形式で返す。
     *
     * @param payload  署名対象のペイロード文字列
     * @param secret   署名シークレット
     * @return 署名文字列（"sha256=" + hex）
     */
    public String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return PREFIX + bytesToHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256署名に失敗しました", e);
        }
    }

    /**
     * 署名を検証する。定数時間比較（MessageDigest.isEqual）を使用してタイミング攻撃を防ぐ。
     *
     * @param payload   検証対象のペイロード文字列
     * @param secret    署名シークレット
     * @param signature 検証する署名文字列
     * @return 署名が一致する場合は true
     */
    public boolean verify(String payload, String secret, String signature) {
        String expected = sign(payload, secret);
        // 定数時間比較でタイミング攻撃を防ぐ
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * バイト配列を16進数文字列に変換する。
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
