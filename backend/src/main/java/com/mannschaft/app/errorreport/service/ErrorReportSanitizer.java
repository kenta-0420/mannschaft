package com.mannschaft.app.errorreport.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * F12.5 Phase 2-C — PII 除去 + プロンプトインジェクション抑止サニタイザ。
 *
 * <p>GitHub Issue 作成時 / AI Prompt 構築時の両方で使用される。</p>
 *
 * <p>処理対象:</p>
 * <ul>
 *   <li>メールアドレス → {@code [REDACTED-EMAIL]}</li>
 *   <li>電話番号 → {@code [REDACTED-PHONE]}</li>
 *   <li>IPv4 → {@code [REDACTED-IP]}</li>
 *   <li>UUID → {@code [REDACTED-UUID]}</li>
 *   <li>{@code Authorization: Bearer xxx} 系ヘッダ → {@code [REDACTED-AUTH]}</li>
 *   <li>{@code ?token=xxx} 等のクエリトークン → {@code [REDACTED-TOKEN]}</li>
 *   <li>Cookie 値 → {@code [REDACTED-COOKIE]}</li>
 *   <li>FORBIDDEN_WORDS → {@code [FILTERED]}</li>
 * </ul>
 */
@Component
public class ErrorReportSanitizer {

    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE =
            Pattern.compile("\\b0\\d{1,4}[- ]?\\d{1,4}[- ]?\\d{4}\\b");
    private static final Pattern IPV4 =
            Pattern.compile("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);
    /** Authorization / Bearer / x-api-key 形式のトークン。
     * {@code Authorization: Bearer abc} のように "Authorization:" の後に "Bearer" が続いて
     * その後にトークンが来るケースも 1 ヒットで吸収できるよう、複数のトークンを許容する。 */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization|bearer|x-api-key)[:= ]+(?:bearer\\s+)?\\S+");
    /** {@code ?token=xxx} {@code &access_token=xxx} {@code &apiKey=xxx} 形式。 */
    private static final Pattern QUERY_TOKEN = Pattern.compile(
            "[?&](token|access_token|apiKey)=[^&\\s]+",
            Pattern.CASE_INSENSITIVE);
    /** Cookie / Set-Cookie ヘッダ。 */
    private static final Pattern COOKIE = Pattern.compile(
            "(?i)(cookie|set-cookie)[:= ]+[^\\s;]+");

    private static final List<String> FORBIDDEN = List.of(
            "ignore previous instructions",
            "ignore all instructions",
            "system prompt",
            "disregard",
            "override instructions");

    /** URL パス内の数値 ID。 */
    private static final Pattern PATH_NUMERIC_ID = Pattern.compile("/\\d+");

    /**
     * テキストから PII / 危険語句を除去する。NULL は NULL を返す。
     *
     * @param text 入力テキスト
     * @return サニタイズ済みテキスト
     */
    public String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String result = text;

        // 順序重要: 構造化されたパターンを先に剥がし、汎用 PII は後で処理する。
        result = AUTH_HEADER.matcher(result).replaceAll("[REDACTED-AUTH]");
        result = COOKIE.matcher(result).replaceAll("[REDACTED-COOKIE]");
        result = QUERY_TOKEN.matcher(result).replaceAll("[REDACTED-TOKEN]");
        result = UUID_RE.matcher(result).replaceAll("[REDACTED-UUID]");
        result = EMAIL.matcher(result).replaceAll("[REDACTED-EMAIL]");
        result = PHONE.matcher(result).replaceAll("[REDACTED-PHONE]");
        result = IPV4.matcher(result).replaceAll("[REDACTED-IP]");

        // FORBIDDEN ワードはケースインセンシティブで [FILTERED] に置換。
        for (String forbidden : FORBIDDEN) {
            result = result.replaceAll(
                    "(?i)" + Pattern.quote(forbidden), "[FILTERED]");
        }
        return result;
    }

    /**
     * URL からパス部分のみ取り出し、数値 ID と UUID を {@code /[ID]} {@code /[UUID]} に
     * マスクする。クエリは削除する。NULL / 空 / パース失敗時は元の値を返すか
     * 空文字とする。
     *
     * @param url 入力 URL
     * @return マスク済みパス
     */
    public String sanitizePagePath(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }

        String path;
        try {
            URI uri = URI.create(url);
            path = uri.getPath();
            if (path == null || path.isBlank()) {
                // パスが空の場合は元の URL からクエリだけ削った形に近づける
                int q = url.indexOf('?');
                path = q >= 0 ? url.substring(0, q) : url;
            }
        } catch (Exception e) {
            // パース失敗時はクエリ部のみ落として続行
            int q = url.indexOf('?');
            path = q >= 0 ? url.substring(0, q) : url;
        }

        // UUID を先に置換（数値 ID パターンに食われないように）
        path = UUID_RE.matcher(path).replaceAll("[UUID]");
        path = PATH_NUMERIC_ID.matcher(path).replaceAll("/[ID]");
        return path;
    }
}
