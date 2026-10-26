package com.mannschaft.app.common.util;

/**
 * LIKE クエリ用エスケープユーティリティ。
 * SQL インジェクション防止のため、ユーザー入力のワイルドカード文字をエスケープする。
 */
public final class LikeEscapeUtils {

    private LikeEscapeUtils() {
    }

    /**
     * LIKE パターン用に % _ \ をエスケープする。
     * 呼び出し側で "%" + escape(q) + "%" のように囲むこと。
     *
     * @param input ユーザー入力文字列
     * @return エスケープ済み文字列（nullの場合はnullを返す）
     */
    public static String escape(String input) {
        if (input == null) {
            return null;
        }
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 部分一致用パターン（%input%）を生成する。
     *
     * @param input ユーザー入力文字列
     * @return エスケープ済み部分一致パターン
     */
    public static String contains(String input) {
        return "%" + escape(input) + "%";
    }
}
