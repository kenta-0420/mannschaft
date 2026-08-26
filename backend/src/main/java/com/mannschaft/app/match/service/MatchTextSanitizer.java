package com.mannschaft.app.match.service;

import com.mannschaft.app.common.security.HtmlSanitizer;

/**
 * F08.10 試合イベントの自由記述テキスト（{@code note} / {@code custom_label} / 選手名等）の
 * 入力サニタイズ（03 §C.4b）。
 *
 * <p>方針: <b>制御文字除去 ＋ trim ＋ HTML タグ不可</b>。Vue 自動エスケープに加え、
 * CSV/PDF エクスポート・SSR・ログ出力時の XSS / CSV インジェクション / CRLF サニタイズの対象とする。
 * 任意のリッチテキストを無検証で保存しない（症状を隠さず根治）。</p>
 *
 * <p>HTML タグは {@link HtmlSanitizer#sanitizePlainText} で全除去（純テキスト化）し、
 * 制御文字（改行・タブ・NUL 等）は除去する。これによりログインジェクション（CRLF）も防ぐ。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.4b</p>
 */
public final class MatchTextSanitizer {

    private MatchTextSanitizer() {
    }

    /**
     * 自由記述テキストをサニタイズする。
     *
     * <ul>
     *   <li>{@code null} → {@code null}</li>
     *   <li>HTML タグを全除去（純テキスト化）</li>
     *   <li>制御文字（{@code \p{Cntrl}}・改行/タブ/NUL 等）を除去（CRLF インジェクション対策）</li>
     *   <li>前後空白を trim</li>
     *   <li>結果が空文字なら {@code null}（DB に空文字を残さない）</li>
     * </ul>
     *
     * @param input サニタイズ対象（{@code null} 許容）
     * @return サニタイズ済みテキスト（空なら {@code null}）
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        // 1) HTML タグ全除去（純テキスト化）
        String cleaned = HtmlSanitizer.sanitizePlainText(input);
        if (cleaned == null) {
            return null;
        }
        // 2) 制御文字除去（改行・タブ・NUL 等 → CRLF ログインジェクション対策）
        cleaned = cleaned.replaceAll("\\p{Cntrl}", "");
        // 3) trim
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
