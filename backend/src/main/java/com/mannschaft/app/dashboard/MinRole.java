package com.mannschaft.app.dashboard;

/**
 * F02.2.1: ダッシュボードウィジェットの最低必要ロール（min_role）。
 *
 * ウィジェットを閲覧するために必要な最低ロールを表す。値は3種類のみ:
 * <ul>
 *   <li>{@link #PUBLIC}     誰でも閲覧可（チーム外の認証済みユーザー含む）</li>
 *   <li>{@link #SUPPORTER} サポーター以上のみ閲覧可</li>
 *   <li>{@link #MEMBER}    メンバー以上のみ閲覧可</li>
 * </ul>
 *
 * <p>ADMIN／DEPUTY_ADMIN 限定ウィジェットは本 enum の管理対象外
 * （F02.2 既存の表示ロール仕組みで別途制御）。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §2, §3</p>
 */
public enum MinRole {

    PUBLIC(0),
    SUPPORTER(1),
    MEMBER(2);

    private final int level;

    MinRole(int level) {
        this.level = level;
    }

    /**
     * ロール階層レベル。値が大きいほど強いロール。
     * 比較演算子の代わりに {@link ViewerRole#isAtLeast(MinRole)} で利用される。
     */
    public int getLevel() {
        return level;
    }

    /**
     * 文字列値（DB の VARCHAR）から MinRole に変換する。大文字小文字不問。
     *
     * @param value 文字列値
     * @return 対応する MinRole
     * @throws IllegalArgumentException 未知の値の場合
     */
    public static MinRole fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("MinRole value must not be null");
        }
        return switch (value.toUpperCase()) {
            case "PUBLIC" -> PUBLIC;
            case "SUPPORTER" -> SUPPORTER;
            case "MEMBER" -> MEMBER;
            default -> throw new IllegalArgumentException("Unknown MinRole value: " + value);
        };
    }
}
