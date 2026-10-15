package com.mannschaft.app.common;

import java.util.regex.Pattern;

/**
 * プレーンテキストバリデーションユーティリティ。
 * HTML タグ・スクリプトの混入を検出する（XSS対策）。
 */
public final class PlainTextValidator {

    private static final Pattern HTML_PATTERN = Pattern.compile(
            "<[a-zA-Z/!][^>]*>|&[a-zA-Z#][a-zA-Z0-9]*;|on\\w+=",
            Pattern.CASE_INSENSITIVE
    );

    private PlainTextValidator() {}

    /**
     * テキストに HTML が含まれているか判定する。
     *
     * @param text 検査対象（null は false）
     * @return true = HTML を含む（不正）
     */
    public static boolean containsHtml(String text) {
        if (text == null || text.isBlank()) return false;
        return HTML_PATTERN.matcher(text).find();
    }
}
