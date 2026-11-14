package com.mannschaft.app.organization.api;

import com.mannschaft.app.organization.service.DuplicateNameNormalizer;

/**
 * 組織・チームの同名確認で使う名称正規化の公開窓口。
 *
 * <p>MySQLのTRIMと揃えて両端のU+0020だけを除去し、制御文字や他の空白は保持する。
 * 状態や外部I/Oを持たず、{@code null} は空文字として扱う。</p>
 */
public final class DuplicateNameNormalizerApi {

    private DuplicateNameNormalizerApi() {
    }

    /**
     * 先頭・末尾の半角スペース（U+0020）のみを除去する。
     *
     * @param value 正規化対象の文字列（{@code null} 可）
     * @return 先頭・末尾の半角スペースのみを除去した文字列
     */
    public static String trimSpaces(String value) {
        return DuplicateNameNormalizer.trimSpaces(value);
    }
}
