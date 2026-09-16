package com.mannschaft.app.common.duplicatename;

/**
 * CMP-260901-1538 柱③-A 検分第5巡是正: 同名確認フロー全体で使う「唯一の正規化」。
 *
 * <p>Java の {@link String#trim()} は制御文字（タブ・改行等、コードポイントが U+0020 以下の
 * 全ての文字）を除去するのに対し、MySQL の {@code TRIM()} 関数は<b>半角スペース（U+0020）
 * のみ</b>を除去する。両者を混在させると、例えば {@code "foo\t"}（末尾タブ）は
 * Java 側では {@code "foo"} に正規化されるが、MySQL 側の {@code TRIM("foo\t")} は
 * {@code "foo\t"} のまま（タブは除去されない）になり、ロックキー生成（Java 側）と
 * 候補検索（DB 側）の正規化基準が食い違う。この食い違いを悪用すると、
 * {@code "foo\t"} で作成したロックキーは既存の {@code "foo"} と一致してしまう
 * （Java trim で一致）一方、候補検索（DB の TRIM 相当）では {@code "foo\t"} が
 * {@code "foo"} と別名として扱われ候補に挙がらず、確認フローを迂回できてしまう。</p>
 *
 * <p>そのため、同名確認フロー（{@link DuplicateNameGuardServiceImpl}・候補検索・
 * fingerprint 計算）全体で<b>この {@link #trimSpaces(String)} だけ</b>を正規化として使う
 * （Java の {@code String#trim()} は同名確認フローの文脈では使わない）。
 * DB 側の生成列 {@code name_trimmed}（{@code GENERATED ALWAYS AS (TRIM(name)) STORED}。
 * V201/V202 マイグレーション）は MySQL {@code TRIM()} と同じ規則（半角スペースのみ）で
 * 格納名を正規化しているため、本メソッドと基準が一致する。</p>
 */
public final class DuplicateNameNormalizer {

    private static final char SPACE = ' ';

    private DuplicateNameNormalizer() {
    }

    /**
     * 先頭・末尾の半角スペース（U+0020）のみを除去する。MySQL の {@code TRIM()} と同じ規則
     * （タブ・改行等の制御文字は除去しない）。{@code null} は空文字として扱う。
     *
     * @param value 正規化対象の文字列（{@code null} 可）
     * @return 先頭・末尾の半角スペースのみを除去した文字列
     */
    public static String trimSpaces(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(start, end);
    }
}
