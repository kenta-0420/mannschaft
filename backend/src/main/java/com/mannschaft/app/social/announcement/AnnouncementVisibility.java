package com.mannschaft.app.social.announcement;

import java.util.Set;

/**
 * お知らせフィード（{@code announcement_feeds.visibility}）の可視性レベルと、
 * 閲覧者ロールごとの「閲覧できる可視性集合」の正準マッピングを集約するユーティリティ（F02.6 §6.2）。
 *
 * <p>
 * 本機能の可視性ラダーは正準ラダー（F00 §5.1.5 / W0 設計書）の名称に揃える:
 * </p>
 * <ul>
 *   <li>{@link #PUBLIC} — 全員（未ログイン含む）に見える</li>
 *   <li>{@link #SUPPORTERS_AND_ABOVE} — SUPPORTER 以上に見える</li>
 *   <li>{@link #MEMBERS_AND_ABOVE} — MEMBER 以上に見える（<b>SUPPORTER には見えない＝「内輪」</b>）</li>
 * </ul>
 *
 * <p>
 * <b>F00 正準（{@code StandardVisibility}）との関係</b>:
 * お知らせフィードは F00 {@code AbstractContentVisibilityResolver} を経由せず、独自 String 値で
 * 可視性を保持する（履歴上の経緯）。ただし「応援者に見せない内輪」を表す本機能の
 * {@link #MEMBERS_AND_ABOVE} は、F00 正準ラダーの {@code StandardVisibility.MEMBERS_AND_ABOVE}
 * （= {@code hasRoleOrAbove(MEMBER)} / SUPPORTER・GUEST 除外）と<b>同一の閾値セマンティクス</b>に揃う。
 * 旧 String 値 {@code "MEMBERS_ONLY"} は正準ラダー名でないため {@code "MEMBERS_AND_ABOVE"} に改称した
 * （W2 改修 / 2026-06。挙動＝SUPPORTER 除外は不変、名前のみ正準化）。
 * </p>
 *
 * <p>
 * ゆえに、各閲覧者ロールが「閲覧できる可視性集合」は次のとおり:
 * </p>
 * <table border="1">
 *   <caption>閲覧者ロール → 可視な visibility 集合</caption>
 *   <tr><th>閲覧者ロール</th><th>閲覧できる visibility</th></tr>
 *   <tr><td>未ログイン / PUBLIC（ロールなし）</td><td>{PUBLIC}</td></tr>
 *   <tr><td>SUPPORTER</td><td>{PUBLIC, SUPPORTERS_AND_ABOVE}</td></tr>
 *   <tr><td>MEMBER 以上（MEMBER/DEPUTY_ADMIN/ADMIN/SYSTEM_ADMIN）</td><td>{PUBLIC, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE}</td></tr>
 * </table>
 *
 * <p>
 * <b>設計意図</b>: 従来は「閲覧者ロール → 単一 visibility 文字列」を渡し、Repository 側の述語が
 * その文字列を誤って解釈していたため、SUPPORTER に「内輪」が露出し（漏洩）、
 * かつ MEMBER 以上が PUBLIC/SUPPORTERS_AND_ABOVE を取りこぼす（逆バグ）二重の欠陥があった。
 * 本クラスは「閲覧者が見られる集合」を一意に算出することで、両方を同時に根治する。
 * 共通定義を 1 箇所に集約し、Repository / Service / inbox アダプタの写経複製を排除する。
 * </p>
 */
public final class AnnouncementVisibility {

    /** 全員（未ログイン含む）が閲覧可能。 */
    public static final String PUBLIC = "PUBLIC";

    /** SUPPORTER 以上が閲覧可能。 */
    public static final String SUPPORTERS_AND_ABOVE = "SUPPORTERS_AND_ABOVE";

    /**
     * MEMBER 以上が閲覧可能（<b>SUPPORTER は不可＝「内輪」</b>）。
     *
     * <p>正準ラダー {@code StandardVisibility.MEMBERS_AND_ABOVE} と同一閾値。
     * 旧 String 値 {@code "MEMBERS_ONLY"} から W2 改修で改称（挙動不変）。</p>
     */
    public static final String MEMBERS_AND_ABOVE = "MEMBERS_AND_ABOVE";

    /** 未ログイン / ロールなし閲覧者が見られる集合: {PUBLIC}。 */
    private static final Set<String> PUBLIC_VIEWER = Set.of(PUBLIC);

    /** SUPPORTER 閲覧者が見られる集合: {PUBLIC, SUPPORTERS_AND_ABOVE}。 */
    private static final Set<String> SUPPORTER_VIEWER = Set.of(PUBLIC, SUPPORTERS_AND_ABOVE);

    /** MEMBER 以上の閲覧者が見られる集合: {PUBLIC, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE}。 */
    private static final Set<String> MEMBER_OR_ABOVE_VIEWER =
            Set.of(PUBLIC, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE);

    private AnnouncementVisibility() {
    }

    /**
     * 閲覧者ロール名から、その閲覧者が閲覧できる visibility 値の集合を返す。
     *
     * <p>ロール名は {@code RoleResolver}/{@code ViewerRole} が産出する値
     * （{@code SYSTEM_ADMIN}/{@code ADMIN}/{@code DEPUTY_ADMIN}/{@code MEMBER}/{@code SUPPORTER}/{@code PUBLIC}）
     * を受け取る。大文字小文字は不問。null・未知の値は安全側に倒して {@code {PUBLIC}}（最小集合）を返す。</p>
     *
     * @param viewerRoleName 閲覧者ロール名（null 可）
     * @return 閲覧できる visibility 値の集合（不変・非 null）
     */
    public static Set<String> allowedFor(String viewerRoleName) {
        if (viewerRoleName == null) {
            return PUBLIC_VIEWER;
        }
        return switch (viewerRoleName.toUpperCase()) {
            case "MEMBER", "DEPUTY_ADMIN", "ADMIN", "SYSTEM_ADMIN" -> MEMBER_OR_ABOVE_VIEWER;
            case "SUPPORTER" -> SUPPORTER_VIEWER;
            // PUBLIC / GUEST / 未知 → 最小集合（安全側）
            default -> PUBLIC_VIEWER;
        };
    }

    /**
     * 当該 feed の visibility が、指定の閲覧者ロールに対して可視かを判定する。
     *
     * @param feedVisibility feed の visibility 値（null は不可視扱い）
     * @param viewerRoleName 閲覧者ロール名（null 可）
     * @return 閲覧可能なら true
     */
    public static boolean isVisibleTo(String feedVisibility, String viewerRoleName) {
        if (feedVisibility == null) {
            return false;
        }
        return allowedFor(viewerRoleName).contains(feedVisibility);
    }
}
