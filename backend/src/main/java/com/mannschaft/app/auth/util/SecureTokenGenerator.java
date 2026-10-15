package com.mannschaft.app.auth.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * セキュアトークン生成ユーティリティ。
 * SecureRandom で 32 バイトを生成し、hex 文字列として返す。
 *
 * <p>セキュリティ重要: トークン長 32 バイト・SHA 後の hex 表現は AuthService から移送した
 * byte-identical な実装である。バイト数・エンコーディングを変更してはならない。</p>
 */
public final class SecureTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;

    private SecureTokenGenerator() {
        // utility class
    }

    /**
     * SecureRandom で 32 バイトを生成し、hex 文字列として返す。
     *
     * @return 64文字の hex 文字列
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
