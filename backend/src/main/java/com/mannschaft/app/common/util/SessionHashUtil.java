package com.mannschaft.app.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SessionHashUtil {

    private SessionHashUtil() {}

    /**
     * refresh token の JTI を SHA-256 でハッシュ化する。
     * audit_logs.session_hash に格納するための識別子として使用する。
     *
     * @param jti refresh token の JWT ID
     * @return SHA-256 ハッシュの16進数文字列（64文字）
     */
    public static String hash(String jti) {
        if (jti == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(jti.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
