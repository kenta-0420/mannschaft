package com.mannschaft.app.dashboard;

/**
 * F02.2.1: ダッシュボード閲覧者のロール。
 *
 * RoleResolver.resolveViewerRole で算出される、スコープに対する閲覧者の有効ロール。
 * F01.2 の {@code AccessControlService.getRoleName} の戻り値を6値に正規化したもの。
 *
 * <ul>
 *   <li>{@link #PUBLIC}        ロールなし／GUEST（認証済みだがスコープに対するロールなし）</li>
 *   <li>{@link #SUPPORTER}    サポーター</li>
 *   <li>{@link #MEMBER}       メンバー</li>
 *   <li>{@link #DEPUTY_ADMIN} 副管理者</li>
 *   <li>{@link #ADMIN}         管理者</li>
 *   <li>{@link #SYSTEM_ADMIN} システム管理者（全権バイパス）</li>
 * </ul>
 *
 * <p>ロール階層（強い順）:</p>
 * <pre>
 * SYSTEM_ADMIN ＞ ADMIN ＞ DEPUTY_ADMIN ＞ MEMBER ＞ SUPPORTER ＞ PUBLIC
 * </pre>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §2, §5</p>
 */
public enum ViewerRole {

    PUBLIC(0),
    SUPPORTER(1),
    MEMBER(2),
    DEPUTY_ADMIN(3),
    ADMIN(4),
    SYSTEM_ADMIN(5);

    private final int level;

    ViewerRole(int level) {
        this.level = level;
    }

    /**
     * ロール階層レベル。値が大きいほど強いロール。
     */
    public int getLevel() {
        return level;
    }

    /**
     * 閲覧者ロールが指定の最低必要ロール以上かを判定する。
     *
     * <p>判定例:
     * <ul>
     *   <li>{@code MEMBER.isAtLeast(MinRole.SUPPORTER)} → true（MEMBER は SUPPORTER 以上）</li>
     *   <li>{@code SUPPORTER.isAtLeast(MinRole.MEMBER)} → false</li>
     *   <li>{@code PUBLIC.isAtLeast(MinRole.PUBLIC)} → true</li>
     *   <li>{@code ADMIN.isAtLeast(MinRole.MEMBER)} → true（ADMIN は MEMBER 以上）</li>
     * </ul>
     *
     * @param minRole 最低必要ロール
     * @return 閲覧可能なら true
     */
    public boolean isAtLeast(MinRole minRole) {
        if (minRole == null) {
            throw new IllegalArgumentException("MinRole must not be null");
        }
        return this.level >= minRole.getLevel();
    }

    /**
     * ADMIN または DEPUTY_ADMIN または SYSTEM_ADMIN のいずれかかを判定する。
     * 管理者は可視性チェックをバイパスして全ウィジェットを閲覧できる。
     */
    public boolean isAdminOrAbove() {
        return this == DEPUTY_ADMIN || this == ADMIN || this == SYSTEM_ADMIN;
    }

    /**
     * 文字列値から ViewerRole に変換する。大文字小文字不問。
     *
     * @param value 文字列値
     * @return 対応する ViewerRole
     * @throws IllegalArgumentException 未知の値の場合
     */
    public static ViewerRole fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ViewerRole value must not be null");
        }
        return switch (value.toUpperCase()) {
            case "PUBLIC" -> PUBLIC;
            case "SUPPORTER" -> SUPPORTER;
            case "MEMBER" -> MEMBER;
            case "DEPUTY_ADMIN" -> DEPUTY_ADMIN;
            case "ADMIN" -> ADMIN;
            case "SYSTEM_ADMIN" -> SYSTEM_ADMIN;
            default -> throw new IllegalArgumentException("Unknown ViewerRole value: " + value);
        };
    }
}
