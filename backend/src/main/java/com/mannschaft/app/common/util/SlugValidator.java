package com.mannschaft.app.common.util;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * チーム・組織の「ユーザーが任意に決める slug」を検証するユーティリティ。
 *
 * <p>村（{@code VillageCreateRequest.slug}）と同じく、ユーザーが作成時に任意の slug を
 * 指定できるようにするための共通検証ロジックを提供する。村は形式チェックのみだが、
 * チーム・組織はさらに <strong>予約語チェック</strong>（フロントエンドの固定ルートと衝突する
 * セグメントの禁止）を行う。</p>
 *
 * <p>形式ルール（村の {@code ^[a-z0-9-]{3,40}$} より厳格）:
 * <ul>
 *   <li>英小文字・数字・ハイフンのみ</li>
 *   <li>長さ 3〜30 文字</li>
 *   <li>先頭・末尾にハイフン不可</li>
 *   <li>連続ハイフン不可</li>
 * </ul>
 * これらを 1 つの正規表現 {@code ^[a-z0-9]+(-[a-z0-9]+)*$}（長さは別途検査）で表現する。</p>
 */
public final class SlugValidator {

    /** slug の最小長。 */
    public static final int MIN_LENGTH = 3;

    /** slug の最大長。 */
    public static final int MAX_LENGTH = 30;

    /**
     * 形式パターン: 英小文字・数字のグループをハイフンで連結。
     * 先頭・末尾ハイフン不可・連続ハイフン不可をこの 1 本で表現する。長さは別途検査。
     */
    private static final Pattern FORMAT_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * 予約語マスタ（小文字）。
     *
     * <p>フロントエンドの固定ルートセグメント（{@code /teams/index}・{@code /teams/search}・
     * {@code /organizations/search} 等）や、一般的に URL 衝突しやすい語を禁止する。
     * slug は {@code /teams/{slug}} / {@code /organizations/{slug}} の動的セグメントに
     * 入るため、これらの語を slug として許すと固定ルートに食われて到達不能になる。</p>
     */
    public static final Set<String> RESERVED_SLUGS = Set.of(
            // 共通 / 一般的に危険な語
            "new", "search", "admin", "settings", "me", "public", "api",
            "login", "register", "logout", "signup", "signin",
            // slug 可用性／301 解決 API のパスセグメント（動的 {slug} に食われないよう予約）
            "slug-available", "slug-resolve",
            // FE 固定セグメント（frontend/app/pages/teams, organizations 直下）
            "index", "create", "edit", "delete", "list",
            // 予防的に押さえておく語
            "system-admin", "dashboard", "discover", "help", "about",
            "static", "assets", "_nuxt", "auth", "oauth", "callback"
    );

    private SlugValidator() {
    }

    /**
     * slug の形式が妥当か判定する（予約語・一意性は含まない）。
     *
     * @param slug 検査対象（null・空文字は不正）
     * @return 形式が妥当なら true
     */
    public static boolean isValidFormat(String slug) {
        if (slug == null) {
            return false;
        }
        int len = slug.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return false;
        }
        return FORMAT_PATTERN.matcher(slug).matches();
    }

    /**
     * slug が予約語に該当するか判定する。
     *
     * @param slug 検査対象
     * @return 予約語なら true
     */
    public static boolean isReserved(String slug) {
        return slug != null && RESERVED_SLUGS.contains(slug.toLowerCase());
    }

    /**
     * ユーザー入力 slug が「指定された」とみなせるか判定する。
     *
     * <p>null または空白のみの場合は「未指定」とし、呼び出し側で自動生成へフォールバックする。</p>
     *
     * @param slug ユーザー入力 slug
     * @return 指定されていれば true
     */
    public static boolean isProvided(String slug) {
        return slug != null && !slug.isBlank();
    }
}
