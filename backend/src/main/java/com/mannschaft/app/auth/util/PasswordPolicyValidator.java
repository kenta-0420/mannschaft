package com.mannschaft.app.auth.util;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.common.BusinessException;

import java.util.regex.Pattern;

/**
 * パスワードポリシー検証ユーティリティ。
 * 8文字以上 + 大文字/小文字/数字/記号のうち3種以上必須。
 *
 * <p>セキュリティ重要: 検証ロジックは AuthService から移送した byte-identical な実装である。
 * 閾値や判定順序を変更してはならない。</p>
 */
public final class PasswordPolicyValidator {

    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[^A-Za-z0-9]");

    private PasswordPolicyValidator() {
        // utility class
    }

    /**
     * パスワードポリシーを検証する。
     * 8文字以上 + 大文字/小文字/数字/記号のうち3種以上必須。
     *
     * @param password 検証対象パスワード
     * @throws BusinessException AUTH_008 ポリシー違反時
     */
    public static void validate(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessException(AuthErrorCode.AUTH_008);
        }

        int typeCount = 0;
        if (UPPERCASE_PATTERN.matcher(password).find()) typeCount++;
        if (LOWERCASE_PATTERN.matcher(password).find()) typeCount++;
        if (DIGIT_PATTERN.matcher(password).find()) typeCount++;
        if (SYMBOL_PATTERN.matcher(password).find()) typeCount++;

        if (typeCount < 3) {
            throw new BusinessException(AuthErrorCode.AUTH_008);
        }
    }
}
