package com.mannschaft.app.common.dto;

/**
 * slug 解決（301 リダイレクト判定）レスポンス（F01.2 §5.9.5）。
 *
 * <p>公開ページが旧 URL アクセス時に「現行 slug へ 301 すべきか」を判定するための最小レスポンス。
 * スコープ漏洩を避けるため、名前など実データは一切返さず {@code canonicalSlug} のみを返す
 * （実データの取得は別途 {@code getTeam} / {@code getOrganization} の認可が守る）。</p>
 *
 * @param status        解決結果。{@code CURRENT}=指定 slug が現行で有効 / {@code MOVED}=旧 slug で
 *                      新 slug へ移動済み（{@code canonicalSlug} に現行 slug） / {@code NOT_FOUND}=該当なし
 * @param canonicalSlug {@code MOVED} のときの現行 slug。それ以外は null
 */
public record SlugResolveResponse(Status status, String canonicalSlug) {

    /** 解決ステータス。 */
    public enum Status {
        /** 指定 slug がそのまま現行で有効（リダイレクト不要）。 */
        CURRENT,
        /** 旧 slug で、現行 slug（canonicalSlug）へ 301 移動すべき。 */
        MOVED,
        /** 現行にも履歴にも該当なし（404 相当）。 */
        NOT_FOUND
    }

    /** 現行 slug が有効なケース。 */
    public static SlugResolveResponse current() {
        return new SlugResolveResponse(Status.CURRENT, null);
    }

    /** 旧 slug → 現行 slug への移動ケース。 */
    public static SlugResolveResponse moved(String canonicalSlug) {
        return new SlugResolveResponse(Status.MOVED, canonicalSlug);
    }

    /** 該当なしのケース。 */
    public static SlugResolveResponse notFound() {
        return new SlugResolveResponse(Status.NOT_FOUND, null);
    }
}
