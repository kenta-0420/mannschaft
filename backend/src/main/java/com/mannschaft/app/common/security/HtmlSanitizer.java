package com.mannschaft.app.common.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * HTML サニタイズ共通ユーティリティ。
 *
 * <p>F02.5 publish-daily の {@code extra_comment} 用に新設。
 * 2026-04-09 時点で {@code TimelinePostService} 側には HTML サニタイズの共通実装が
 * 存在しないため、本クラスを最初に整備した。<b>F04.1 タイムラインへの統合は将来検討</b>
 * （横断的責務のため、F02.5 の独自実装にはせず {@code common/security} 配下に置く）。</p>
 *
 * <p>Jsoup の {@link Safelist} を用いて以下の2モードを提供する:</p>
 * <ul>
 *   <li>{@link #sanitizePlainText(String)} — {@link Safelist#none()} 相当。
 *       タグ類は全て除去し、純テキストのみを残す。publish-daily の extra_comment のような
 *       「ユーザーが書いた自由テキスト」を投稿本文に埋め込む場合に利用する</li>
 *   <li>{@link #sanitizeBasic(String)} — {@link Safelist#basic()} 相当。
 *       {@code <b>/<i>/<a>} などの基本的な整形タグのみを残す。将来タイムラインの
 *       マークダウンレンダリング後の HTML をさらに保険でサニタイズするような用途向け</li>
 * </ul>
 *
 * <p>いずれのメソッドも {@code null} 入力に対しては {@code null} を返し、空文字には
 * 空文字を返す（Jsoup のデフォルト挙動に準じるが明示的に分岐して安全側に倒している）。</p>
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {
        // ユーティリティクラスのためインスタンス化禁止
    }

    /**
     * 入力文字列からすべての HTML タグを除去し、純テキストのみを返す。
     *
     * <p>{@code <script>}/{@code <a>}/{@code <b>} など、あらゆるタグが除去される。
     * publish-daily の extra_comment は投稿本文に埋め込まれるが、タイムライン表示側が
     * エスケープ漏れを起こした場合でも XSS を成立させないために、ここで確実にタグを落とす。</p>
     *
     * @param input サニタイズ対象文字列（{@code null} 許容）
     * @return すべてのタグを除去した結果。{@code null} 入力は {@code null} を返す
     */
    public static String sanitizePlainText(String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty()) {
            return "";
        }
        return Jsoup.clean(input, Safelist.none());
    }

    /**
     * 入力文字列に対して {@link Safelist#basic()} 相当のサニタイズを行う。
     *
     * <p>{@code <b>/<em>/<i>/<strong>/<a>}（リンク）など最小限の整形タグのみを残し、
     * {@code <script>/<style>/<iframe>} などの危険タグは除去される。</p>
     *
     * @param input サニタイズ対象文字列（{@code null} 許容）
     * @return 基本タグのみを残した HTML。{@code null} 入力は {@code null} を返す
     */
    public static String sanitizeBasic(String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty()) {
            return "";
        }
        return Jsoup.clean(input, Safelist.basic());
    }
}
