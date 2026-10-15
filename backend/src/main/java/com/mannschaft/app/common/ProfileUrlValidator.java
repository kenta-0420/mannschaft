package com.mannschaft.app.common;

import java.util.regex.Pattern;

/**
 * プロフィール URL バリデーションユーティリティ。
 * homepage_url のスキーム検証（http/https のみ許可）。
 */
public final class ProfileUrlValidator {

    private static final Pattern ALLOWED_SCHEME = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private ProfileUrlValidator() {}

    /**
     * URL が http:// または https:// で始まるか検証する。
     *
     * @param url バリデーション対象（null は許可 → 削除扱い）
     * @return true = 有効
     */
    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) return true; // null/空は valid（削除扱い）
        return ALLOWED_SCHEME.matcher(url).matches();
    }

    /**
     * URL を保存前に小文字スキームに正規化する。
     * "HTTP://example.com" → "http://example.com"
     *
     * @param url 正規化対象
     * @return 正規化済み URL（null/空は null を返す）
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.toLowerCase().startsWith("https://")) {
            return "https://" + url.substring(url.indexOf("://") + 3);
        }
        if (url.toLowerCase().startsWith("http://")) {
            return "http://" + url.substring(url.indexOf("://") + 3);
        }
        return url;
    }
}
