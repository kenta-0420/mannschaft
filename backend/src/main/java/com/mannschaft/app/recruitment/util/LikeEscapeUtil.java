package com.mannschaft.app.recruitment.util;

/**
 * 募集/市の LIKE 検索向けワイルドカードエスケープユーティリティ。
 *
 * <p>SQL LIKE のワイルドカード（{@code %} 任意長 / {@code _} 任意 1 文字）と、
 * エスケープ文字そのもの（{@code \}）をリテラル文字として扱うため、JPQL の
 * {@code LIKE ... ESCAPE '\'} 句と対になる形でユーザー入力をエスケープする。</p>
 *
 * <p>エスケープしないと、ユーザーが入力した {@code %} / {@code _} がワイルドカードとして
 * 解釈され、本来絞り込むべきフィルタが全件マッチ等に化けてしまう（フィルタ無効化）。</p>
 *
 * <p>適用順は「{@code blankToNull} → {@code escape}」。null / 空白は呼び出し側で
 * null 化済みの前提で、本ユーティリティは null をそのまま透過する（null をエスケープしない）。</p>
 *
 * <p>バックスラッシュを最初に二重化してから {@code %} / {@code _} を前置する点に注意
 * （順序を誤ると挿入した {@code \} を二重に処理してしまう）。</p>
 */
public final class LikeEscapeUtil {

    private LikeEscapeUtil() {
    }

    /**
     * LIKE 検索のワイルドカードをエスケープする。
     *
     * @param value ユーザー入力（null 可）
     * @return エスケープ済み文字列。null はそのまま null を返す
     */
    public static String escape(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
